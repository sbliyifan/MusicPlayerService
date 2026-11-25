package cn.edu.sicnu.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.edu.sicnu.musicplayer.model.Song

/**
 * 歌曲列表适配器。
 *
 * 高亮当前正在播放的行，让用户一眼看出"现在播到哪一首"。
 */
class SongAdapter(
    private val songs: List<Song>,
    private val onItemClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    /** 当前播放行的下标，-1 表示没有 */
    private var playingIndex = -1
    private var isPlaying = false

    fun setPlayingIndex(index: Int) {
        val old = playingIndex
        playingIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (playingIndex >= 0) notifyItemChanged(playingIndex)
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.item_title)
        private val tvArtist: TextView = itemView.findViewById(R.id.item_artist)
        private val tvDuration: TextView = itemView.findViewById(R.id.item_duration)
        private val indicator: View = itemView.findViewById(R.id.item_indicator)

        fun bind(song: Song, position: Int) {
            tvTitle.text = song.title
            tvArtist.text = song.artist
            tvDuration.text = song.durationText()

            val isCurrent = position == playingIndex
            // 当前歌曲：标题高亮 + 左侧竖条显示
            tvTitle.alpha = if (isCurrent) 1f else 0.85f
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            tvDuration.text = if (isCurrent && isPlaying) "▶ " + song.durationText() else song.durationText()

            itemView.setOnClickListener { onItemClick(song, position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size
}