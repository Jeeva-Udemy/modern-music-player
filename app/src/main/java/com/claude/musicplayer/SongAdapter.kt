package com.claude.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit

/**
 * Shared song-row adapter used by the Songs tab, Folder contents, and
 * Playlist contents screens.
 *
 * Selection mode (checkboxes + a "more" menu button) is opt-in per screen:
 * pass [isSelectionMode] etc. to enable multi-select (used for delete /
 * remove-from-playlist flows); leave the defaults for a plain tap-to-play list.
 *
 * Pass [nowPlayingPath] to highlight whichever row is currently playing —
 * defaults to no-highlight for screens that don't track it.
 */
class SongAdapter(
    private var songs: List<Song>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {},
    private val onMoreClick: ((Int, View) -> Unit)? = null,
    private val isSelectionMode: () -> Boolean = { false },
    private val isSelected: (Song) -> Boolean = { false },
    private val onToggleSelect: (Song) -> Unit = {},
    private val nowPlayingPath: () -> String? = { null },
    private val nowPlayingActive: () -> Boolean = { false }
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val title: TextView = view.findViewById(R.id.songTitle)
        val subtitle: TextView = view.findViewById(R.id.songSubtitle)
        val art: ImageView = view.findViewById(R.id.songArt)
        val duration: TextView = view.findViewById(R.id.songDuration)
        val checkbox: CheckBox = view.findViewById(R.id.songCheckbox)
        val moreButton: ImageButton = view.findViewById(R.id.songMore)
        val nowPlayingIcon: ImageView = view.findViewById(R.id.songNowPlayingIcon)
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
        holder.art.imageTintList = android.content.res.ColorStateList.valueOf(
            holder.itemView.context.getColor(R.color.accent)
        )
        holder.art.setImageResource(R.drawable.ic_music_note)
        val bitmap = MusicRepository.loadAlbumArt(holder.itemView.context, song.albumArtUri)
        if (bitmap != null) {
            holder.art.imageTintList = null
            holder.art.setImageBitmap(bitmap)
        }

        val selectionActive = isSelectionMode()
        holder.checkbox.visibility = if (selectionActive) View.VISIBLE else View.GONE
        holder.moreButton.visibility = if (selectionActive || onMoreClick == null) View.GONE else View.VISIBLE

        // Highlight whichever song is currently loaded in the player — the
        // only way to tell otherwise was the mini player bar, which isn't
        // visible while scrolling through a long list.
        val isNowPlaying = song.path == nowPlayingPath() && nowPlayingPath() != null
        if (isNowPlaying) {
            holder.card.strokeColor = holder.itemView.context.getColor(R.color.accent)
            holder.card.strokeWidth = holder.itemView.resources.getDimensionPixelSize(R.dimen.now_playing_stroke_width)
            holder.title.setTextColor(holder.itemView.context.getColor(R.color.accent))
            holder.nowPlayingIcon.visibility = View.VISIBLE
            holder.nowPlayingIcon.setImageResource(
                if (nowPlayingActive()) R.drawable.ic_equalizer else R.drawable.ic_play
            )
            holder.duration.visibility = View.GONE
        } else {
            holder.card.strokeWidth = 0
            holder.title.setTextColor(holder.itemView.context.getColor(R.color.textPrimary))
            holder.nowPlayingIcon.visibility = View.GONE
            holder.duration.visibility = if (selectionActive) View.GONE else View.VISIBLE
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = isSelected(song)
        holder.checkbox.setOnCheckedChangeListener { _, _ -> onToggleSelect(song) }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (isSelectionMode()) onToggleSelect(songs[pos]) else onClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
            true
        }
        holder.moreButton.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onMoreClick?.invoke(pos, v)
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateData(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    /** Call after selection mode or the selected set changes, to redraw checkboxes/menus. */
    fun refreshSelectionUi() {
        notifyDataSetChanged()
    }

    /** Call when the currently-playing song or its play/pause state changes. */
    fun refreshNowPlaying() {
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    private fun formatDuration(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
