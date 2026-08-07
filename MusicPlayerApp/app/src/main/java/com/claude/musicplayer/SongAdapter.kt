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
        holder.art.setImageResource(R.drawable.ic_music_note)
        try {
            song.albumArtUri?.let {
                holder.art.setImageURI(android.net.Uri.parse(it))
            }
        } catch (e: Exception) { /* keep placeholder */ }

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
