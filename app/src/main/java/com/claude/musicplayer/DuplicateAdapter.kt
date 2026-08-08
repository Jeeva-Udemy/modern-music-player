package com.claude.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

sealed class DuplicateRow {
    data class Header(val title: String, val artist: String, val count: Int) : DuplicateRow()
    data class SongRow(val song: Song) : DuplicateRow()
}

class DuplicateAdapter(
    private var rows: List<DuplicateRow>,
    private val isSelected: (Song) -> Boolean,
    private val onToggle: (Song) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SONG = 1
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.duplicateHeaderLabel)
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.duplicateCheckbox)
        val path: TextView = view.findViewById(R.id.duplicatePath)
        val size: TextView = view.findViewById(R.id.duplicateSize)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DuplicateRow.Header -> TYPE_HEADER
        is DuplicateRow.SongRow -> TYPE_SONG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_duplicate_header, parent, false))
        } else {
            SongViewHolder(inflater.inflate(R.layout.item_duplicate_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DuplicateRow.Header -> {
                holder as HeaderViewHolder
                holder.label.text = "${row.title} — ${row.artist}  (${row.count} copies)"
            }
            is DuplicateRow.SongRow -> {
                holder as SongViewHolder
                val song = row.song
                holder.path.text = song.folder
                val sizeMb = File(song.path).length() / (1024.0 * 1024.0)
                holder.size.text = String.format("%.1f MB", sizeMb)
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = isSelected(song)
                holder.checkbox.setOnCheckedChangeListener { _, _ -> onToggle(song) }
                holder.itemView.setOnClickListener { holder.checkbox.toggle() }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    fun updateData(newRows: List<DuplicateRow>) {
        rows = newRows
        notifyDataSetChanged()
    }
}
