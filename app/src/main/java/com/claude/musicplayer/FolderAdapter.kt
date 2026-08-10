package com.claude.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FolderAdapter(
    private var folders: List<Map.Entry<String, List<Song>>>,
    private val onClick: (String, List<Song>) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.folderName)
        val path: TextView = view.findViewById(R.id.folderPath)
        val count: TextView = view.findViewById(R.id.folderCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val (fullPath, songs) = folders[position]
        holder.name.text = File(fullPath).name
        holder.path.text = fullPath
        holder.count.text = "${songs.size} songs"
        holder.itemView.setOnClickListener { onClick(fullPath, songs) }
    }

    override fun getItemCount(): Int = folders.size

    fun updateData(newFolders: Map<String, List<Song>>) {
        folders = newFolders.entries.toList()
        notifyDataSetChanged()
    }
}
