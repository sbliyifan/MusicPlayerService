package cn.edu.sicnu.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import cn.edu.sicnu.musicplayer.model.Song

/**
 * 音乐列表扫描工具（对应实验要求第 1 条：使用 ContentResolver 获得音乐文件列表）。
 *
 * 原理：
 *   Android 把所有媒体文件的元数据存在系统的 MediaStore 数据库里，
 *   应用通过 ContentResolver.query() 查询 MediaStore.Audio.Media.EXTERNAL_CONTENT_URI，
 *   就能拿到系统媒体库中的歌曲信息，不需要自己遍历文件系统（Android 10 起也不允许）。
 *
 * 注意：
 *   - 查询前必须已获得 READ_MEDIA_AUDIO（API 33+）或 READ_EXTERNAL_STORAGE（API 32-）权限；
 *   - 只筛 IS_MUSIC != 0 的记录，避免把铃声、通知音也扫进来；
 *   - 查询要放在子线程（本类由调用方在协程/线程中执行）。
 */
object MusicScanner {

    /** 需要查询的列 */
    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
    )

    /** 只取音乐，过滤掉短于 30 秒的提示音 */
    private const val SELECTION =
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"

    private const val SORT_ORDER = "${MediaStore.Audio.Media.TITLE} ASC"

    /**
     * 查询设备上的音乐列表。
     *
     * @return 歌曲列表；没有权限或没有音乐时返回空列表
     */
    fun scan(context: Context): List<Song> {
        val songs = mutableListOf<Song>()

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                SELECTION,
                null,
                SORT_ORDER
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    // 用 id 拼出这条记录的 content Uri，播放时交给 MediaPlayer
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    songs.add(
                        Song(
                            id = id,
                            title = cursor.getString(titleCol) ?: "未知歌曲",
                            artist = cursor.getString(artistCol) ?: "未知艺术家",
                            album = cursor.getString(albumCol) ?: "未知专辑",
                            duration = cursor.getLong(durCol),
                            uri = uri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 没有权限时会抛 SecurityException，这里吞掉返回空列表，由界面提示用户授权
            e.printStackTrace()
        }

        return songs
    }
}