package com.claude.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class SongAdapter(
    private var songs: List<Song>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.songTitle)
        val subtitle: TextView = view.findViewById(R.id.songSubtitle)
        val art: ImageView = view.findViewById(R.id.songArt)
        val duration: TextView = view.findViewById(R.id.songDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.subtitle.text = song.artist
        holder.duration.text = formatDuration(song.duration)

        // Always start from the placeholder, then only replace it if we can
        // actually decode real album art — many tracks have none, and the
        // legacy MediaStore art URI can "resolve" without throwing while
        // still decoding to nothing, which used to leave the row blank.
        //
        // Also: a persistent app:tint on this ImageView (previously in the
        // XML) was recoloring EVERY bitmap loaded into it afterward, not
        // just the placeholder — which painted real album art solid purple
        // and made it look like it wasn't loading at all. Tint is now only
        // ever applied here, alongside the placeholder, and explicitly
        // cleared whenever real art loads.
        holder.art.imageTintList = android.content.res.ColorStateList.valueOf(
            holder.itemView.context.getColor(R.color.accent)
        )
        holder.art.setImageResource(R.drawable.ic_music_note)
        val bitmap = MusicRepository.loadAlbumArt(holder.itemView.context, song.albumArtUri)
        if (bitmap != null) {
            holder.art.imageTintList = null
            holder.art.setImageBitmap(bitmap)
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
            true
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateData(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    private fun formatDuration(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
