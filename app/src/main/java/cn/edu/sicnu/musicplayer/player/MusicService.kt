package cn.edu.sicnu.musicplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.edu.sicnu.musicplayer.MainActivity
import cn.edu.sicnu.musicplayer.R
import cn.edu.sicnu.musicplayer.model.Song
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 音乐播放服务。
 *
 * 对应实验要求的第 2 条：所有与播放相关的功能都在 Service 中实现，
 * Activity 通过绑定服务拿到 Binder，再由 Binder 向外提供歌曲名、进度等信息。
 *
 * 为什么放在 Service：
 *   Activity 被销毁（如旋转屏幕、退到后台）时，Service 仍然存活，
 *   音乐可以继续播放；同时 Service 不依赖界面，职责更清晰。
 *
 * 关键点：
 *   - onBind 返回自定义 Binder，Activity 通过它调用 play/pause/next 等方法；
 *   - MediaPlayer 的回调发生在子线程，更新界面要通过监听器抛回主线程；
 *   - 进度条用 ScheduledExecutorService 定时（每 500ms）回调，而不是在 UI 线程里轮询；
 *   - 切歌时同步更新 Notification。
 */
class MusicService : Service() {

    /** 对外暴露的 Binder */
    private val binder = MusicBinder()

    private var mediaPlayer: MediaPlayer? = null

    /** 当前播放列表 */
    private var playlist: List<Song> = emptyList()

    /** 当前歌曲下标 */
    private var currentIndex: Int = -1

    /** 是否处于"准备中"（防止连续点击导致状态错乱） */
    private var isPreparing = false

    /** 播放进度回调的定时器 */
    private var progressExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var progressTask: ScheduledFuture<*>? = null

    /** 界面监听器（弱耦合：Service 不知道界面的存在） */
    private var listener: OnPlayerListener? = null

    /** 界面回调接口 */
    interface OnPlayerListener {
        /** 当前歌曲变化 */
        fun onSongChanged(song: Song?)

        /** 播放/暂停状态变化 */
        fun onPlayStateChanged(isPlaying: Boolean)

        /** 进度变化（current 与 duration 单位均为毫秒） */
        fun onProgress(current: Int, duration: Int)
    }

    /**
     * Binder：Activity 通过它拿到 Service 实例并调用播放方法。
     * 这是"绑定服务"通信的核心——不通过 Intent 传数据，而是直接方法调用。
     */
    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    // ==================== 对外公开的 API（供 Binder 调用） ====================

    /** 设置播放列表并从 startIndex 开始播放 */
    fun playFromList(songs: List<Song>, startIndex: Int) {
        this.playlist = songs
        this.currentIndex = startIndex
        playCurrent()
    }

    fun togglePlayPause() = togglePlayPauseInternal()

    /** 播放/暂停切换（供 Activity 调用，语义更清晰） */
    fun playPause() = togglePlayPauseInternal()

    fun playNextPublic() = playNext()

    fun playPreviousPublic() = playPrevious()

    fun stopPlayback() = stopPlay()

    fun seekToPosition(ms: Int) = seekTo(ms)

    fun currentSongOrNull(): Song? = currentSong()

    fun isPlayingNow(): Boolean = mediaPlayer?.isPlaying == true

    fun currentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun totalDuration(): Int = mediaPlayer?.duration ?: 0

    fun setPlayerListener(l: OnPlayerListener?) {
        listener = l
    }

    fun clearPlayerListener() {
        listener = null
    }

    /** 内部切换播放/暂停（公开方法见上方的 togglePlayPause） */
    private fun togglePlayPauseInternal() {
        val player = mediaPlayer ?: run {
            if (currentIndex >= 0) playCurrent()
            return
        }
        if (player.isPlaying) pause() else resume()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        progressExecutor = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理通知栏上的动作按钮（点击通知栏的 上一首 / 下一首）
        when (intent?.action) {
            ACTION_PREVIOUS -> playPrevious()
            ACTION_NEXT -> playNext()
        }
        // 不是前台服务时，START_STICKY 让系统在资源紧张后尝试重建服务
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        progressExecutor?.shutdownNow()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ==================== 播放控制 ====================

    /** 播放当前下标对应的歌曲 */
    private fun playCurrent() {
        val song = currentSong() ?: return

        isPreparing = true
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(applicationContext, android.net.Uri.parse(song.uri))
                setOnPreparedListener {
                    isPreparing = false
                    it.start()
                    listener?.onSongChanged(song)
                    listener?.onPlayStateChanged(true)
                    startProgressUpdates()
                    showNotification(song, isPlaying = true)
                }
                setOnCompletionListener {
                    // 播放完成自动下一首；最后一首播完就停在末尾
                    if (currentIndex < playlist.size - 1) {
                        playNext()
                    } else {
                        listener?.onPlayStateChanged(false)
                        showNotification(song, isPlaying = false)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    isPreparing = false
                    listener?.onPlayStateChanged(false)
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "播放失败", e)
                isPreparing = false
            }
        }
    }

    private fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying && !isPreparing) {
                it.start()
                listener?.onPlayStateChanged(true)
                startProgressUpdates()
                currentSong()?.let { s -> showNotification(s, true) }
            }
        }
    }

    private fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                listener?.onPlayStateChanged(false)
                stopProgressUpdates()
                currentSong()?.let { s -> showNotification(s, false) }
            }
        }
    }

    private fun stopPlay() {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause()
        }
        stopProgressUpdates()
        listener?.onPlayStateChanged(false)
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    private fun playPrevious() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        playCurrent()
    }

    private fun seekTo(ms: Int) {
        mediaPlayer?.let { player ->
            if (!isPreparing) {
                player.seekTo(ms)
                listener?.onProgress(ms, player.duration)
            }
        }
    }

    private fun currentSong(): Song? {
        return if (currentIndex in playlist.indices) playlist[currentIndex] else null
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try {
                it.reset()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 MediaPlayer 失败", e)
            }
        }
        mediaPlayer = null
    }

    // ==================== 进度更新（多线程） ====================

    /**
     * 用 ScheduledExecutorService 每 500ms 读一次播放进度并回调。
     * 之所以不用 Activity 里的 Handler 轮询，是因为进度属于"播放状态"，
     * 由 Service 统一维护更合理；而且界面销毁后仍能继续（重新绑定后立刻拿到最新值）。
     */
    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressTask = progressExecutor?.scheduleAtFixedRate({
            val player = mediaPlayer
            if (player != null && !isPreparing) {
                try {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (dur > 0) {
                        listener?.onProgress(pos, dur)
                    }
                } catch (e: IllegalStateException) {
                    // 播放器正好被释放，忽略这一轮
                }
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopProgressUpdates() {
        progressTask?.cancel(false)
        progressTask = null
    }

    // ==================== 通知栏 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW   // 低优先级：常驻但不打扰
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 显示/更新通知栏，把当前歌曲名展示出来（实验要求第 4 条）。
     * 点击通知回到 Activity。
     */
    private fun showNotification(song: Song, isPlaying: Boolean) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(
                getString(
                    R.string.notification_content,
                    song.artist,
                    if (isPlaying) getString(R.string.state_playing) else getString(R.string.state_paused)
                )
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(albumArt())
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 通知栏上放 上一首 / 播放暂停 / 下一首 三个动作
        builder.addAction(0, getString(R.string.action_previous), servicePendingIntent(ACTION_PREVIOUS))
        builder.addAction(0, getString(R.string.action_next), servicePendingIntent(ACTION_NEXT))

        val notification: Notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需要指定前台服务类型
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 通知栏大图标：用内置播放图标生成一张位图，避免依赖外部资源文件 */
    private fun albumArt(): Bitmap? {
        return try {
            BitmapFactory.decodeResource(resources, android.R.drawable.ic_media_play)
                ?.let { src ->
                    Bitmap.createScaledBitmap(src, 128, 128, true)
                }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "MusicService"
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PREVIOUS = "cn.edu.sicnu.musicplayer.PREVIOUS"
        const val ACTION_NEXT = "cn.edu.sicnu.musicplayer.NEXT"
    }
}