package cn.edu.sicnu.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.edu.sicnu.musicplayer.data.MusicScanner
import cn.edu.sicnu.musicplayer.model.Song
import cn.edu.sicnu.musicplayer.player.MusicService
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/**
 * 主界面（Controller 层）。
 *
 * 对应实验要求：
 *   - 用 ContentResolver 拿到音乐列表（交给 MusicScanner）；
 *   - 绑定 MusicService，通过 Binder 拿到服务实例，调用播放/暂停/上下曲，
 *     并获取当前歌曲名、播放进度等信息；
 *   - SeekBar 实时显示进度（数据由 Service 的定时线程推送），拖动可跳转；
 *   - 切歌时 Service 会更新通知栏。
 */
class MainActivity : AppCompatActivity(), MusicService.OnPlayerListener {

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: MaterialButton
    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter

    private var musicService: MusicService? = null
    private var bound = false

    /** 用户正在拖动进度条时为 true，此时不被 Service 的进度回调覆盖 */
    private var isUserSeeking = false

    private val songs = mutableListOf<Song>()

    /** 待播放的歌曲下标（服务尚未绑定完成时暂存） */
    private var pendingIndex = -1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val musicBinder = binder as MusicService.MusicBinder
            val service = musicBinder.getService()
            musicService = service
            bound = true
            service.setPlayerListener(this@MainActivity)

            // 若之前有未完成的播放请求，现在补上
            if (pendingIndex >= 0 && songs.isNotEmpty()) {
                service.playFromList(songs, pendingIndex)
                pendingIndex = -1
            } else {
                // 同步一次当前状态（服务可能已经在播放）
                service.currentSongOrNull()?.let { onSongChanged(it) }
                onPlayStateChanged(service.isPlayingNow())
                val dur = service.totalDuration()
                if (dur > 0) onProgress(service.currentPosition(), dur)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
        }
    }

    /** 音乐文件权限申请结果 */
    private val musicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSongs()
        } else {
            showEmpty(getString(R.string.msg_permission_denied))
        }
    }

    /** 通知权限（Android 13+）申请结果：拒绝也不影响播放，只是看不到通知 */
    private val notifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 忽略结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 应用标题：姓名 + 学号
        supportActionBar?.title = getString(R.string.app_name)

        bindViews()
        requestPermissionOrLoad()
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tv_song_title)
        tvArtist = findViewById(R.id.tv_song_artist)
        tvCurrent = findViewById(R.id.tv_current_time)
        tvTotal = findViewById(R.id.tv_total_time)
        tvEmpty = findViewById(R.id.tv_empty)
        seekBar = findViewById(R.id.seek_bar)
        btnPlay = findViewById(R.id.btn_play)
        rvSongs = findViewById(R.id.rv_songs)

        adapter = SongAdapter(songs) { _, index -> playSongAt(index) }
        rvSongs.layoutManager = LinearLayoutManager(this)
        rvSongs.adapter = adapter

        // 播放 / 暂停
        btnPlay.setOnClickListener {
            val svc = musicService
            if (svc == null) {
                if (songs.isNotEmpty()) playSongAt(0)
                return@setOnClickListener
            }
            if (svc.isPlayingNow()) {
                svc.playPause()          // 正在播放 -> 暂停
            } else if (svc.currentPosition() > 0 && svc.currentSongOrNull() != null) {
                svc.playPause()          // 暂停中 -> 继续
            } else if (songs.isNotEmpty()) {
                playSongAt(0)            // 从未播放过 -> 播第一首
            }
        }

        // 上一首 / 下一首 / 停止
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { musicService?.playPreviousPublic() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { musicService?.playNextPublic() }
        findViewById<ImageButton>(R.id.btn_stop).setOnClickListener { musicService?.stopPlayback() }

        // 拖动进度条
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrent.text = formatTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                sb?.let { musicService?.seekToPosition(it.progress) }
            }
        })
    }

    // ==================== 权限与扫描 ====================

    private fun requestPermissionOrLoad() {
        // Android 13（API 33）起读音乐改用 READ_MEDIA_AUDIO
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            // 已有权限，顺便补申请通知权限
            requestNotificationPermission()
            loadSongs()
        } else {
            musicPermissionLauncher.launch(permission)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 在子线程扫描音乐（ContentResolver 查询可能较慢） */
    private fun loadSongs() {
        requestNotificationPermission()
        showEmpty(getString(R.string.msg_scanning))

        thread {
            val list = MusicScanner.scan(this)
            Handler(Looper.getMainLooper()).post {
                songs.clear()
                songs.addAll(list)
                adapter.notifyDataSetChanged()

                if (songs.isEmpty()) {
                    showEmpty(getString(R.string.msg_no_music))
                } else {
                    tvEmpty.visibility = View.GONE
                    bindMusicService()
                }
            }
        }
    }

    private fun showEmpty(text: String) {
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = text
    }

    // ==================== 绑定服务 ====================

    private fun bindMusicService() {
        if (bound) return
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun playSongAt(index: Int) {
        if (index !in songs.indices) return
        adapter.setPlayingIndex(index)

        if (bound && musicService != null) {
            musicService?.playFromList(songs, index)
        } else {
            // 服务还没绑定好，先记下下标，onServiceConnected 里再播
            pendingIndex = index
            bindMusicService()
        }
    }

    // ==================== Service 回调（切换到主线程更新界面） ====================

    override fun onSongChanged(song: Song?) {
        runOnUiThread {
            tvTitle.text = song?.title ?: getString(R.string.default_song_title)
            tvArtist.text = song?.artist ?: getString(R.string.default_song_artist)
            val idx = songs.indexOfFirst { it.id == song?.id }
            if (idx >= 0) adapter.setPlayingIndex(idx)
        }
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            btnPlay.text = getString(if (isPlaying) R.string.action_pause else R.string.action_play)
            adapter.setPlaying(isPlaying)
        }
    }

    override fun onProgress(current: Int, duration: Int) {
        if (isUserSeeking) return
        runOnUiThread {
            seekBar.max = duration
            seekBar.progress = current
            tvCurrent.text = formatTime(current)
            tvTotal.text = formatTime(duration)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            musicService?.clearPlayerListener()
            unbindService(connection)
            bound = false
        }
    }
}