package cn.edu.sicnu.musicplayer.model

import java.io.Serializable

/**
 * 一首歌。
 *
 * 只保留界面需要的字段，避免把整个 MediaStore 游标传得到处都是。
 * 实现 Serializable 方便后续做状态保存（如旋转屏幕后恢复列表）。
 */
data class Song(
    /** MediaStore 中的 id，用于构造 content Uri */
    val id: Long,
    /** 歌曲标题 */
    val title: String,
    /** 演唱者 */
    val artist: String,
    /** 专辑名 */
    val album: String,
    /** 时长（毫秒） */
    val duration: Long,
    /** 完整播放地址（content://media/...） */
    val uri: String
) : Serializable {

    /** 时长格式化为 mm:ss，用于列表显示 */
    fun durationText(): String {
        val totalSec = duration / 1000
        return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}