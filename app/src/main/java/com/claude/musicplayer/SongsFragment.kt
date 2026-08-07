package com.claude.musicplayer

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SongsFragment : Fragment(R.layout.fragment_songs) {

    private var allSongs: List<Song> = emptyList()
    private lateinit var adapter: SongAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.songsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        allSongs = MusicRepository.loadAllSongs(requireContext())

        adapter = SongAdapter(
            allSongs,
            onClick = { position ->
                PlaybackController.playAndOpenNowPlaying(requireContext(), adapter.currentList(), position)
            },
            onLongClick = { position ->
                PlaylistDialogHelper.showAddToPlaylistDialog(requireContext(), adapter.currentList()[position])
            }
        )
        recyclerView.adapter = adapter

        view.findViewById<TextView>(R.id.sortButton).setOnClickListener { anchor ->
            showSortMenu(anchor)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.sort_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val sorted = when (item.itemId) {
                R.id.sort_title_asc -> allSongs.sortedBy { it.title.lowercase() }
                R.id.sort_title_desc -> allSongs.sortedByDescending { it.title.lowercase() }
                R.id.sort_artist -> allSongs.sortedBy { it.artist.lowercase() }
                R.id.sort_duration -> allSongs.sortedBy { it.duration }
                R.id.sort_newest -> allSongs.sortedByDescending { it.dateAdded }
                R.id.sort_oldest -> allSongs.sortedBy { it.dateAdded }
                else -> allSongs
            }
            adapter.updateData(sorted)
            true
        }
        popup.show()
    }

    fun refresh() {
        allSongs = MusicRepository.loadAllSongs(requireContext())
        adapter.updateData(allSongs)
    }
}
