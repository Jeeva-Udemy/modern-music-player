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
    private var currentSortId: Int = R.id.sort_title_asc

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.songsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        currentSortId = SortPreference.load(requireContext())

        adapter = SongAdapter(
            emptyList(),
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

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.sort_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            currentSortId = item.itemId
            SortPreference.save(requireContext(), currentSortId)
            adapter.updateData(applySort(allSongs, currentSortId))
            true
        }
        popup.show()
    }

    private fun applySort(songs: List<Song>, sortId: Int): List<Song> {
        return when (sortId) {
            R.id.sort_title_asc -> songs.sortedBy { it.title.lowercase() }
            R.id.sort_title_desc -> songs.sortedByDescending { it.title.lowercase() }
            R.id.sort_artist -> songs.sortedBy { it.artist.lowercase() }
            R.id.sort_duration -> songs.sortedBy { it.duration }
            R.id.sort_newest -> songs.sortedByDescending { it.dateAdded }
            R.id.sort_oldest -> songs.sortedBy { it.dateAdded }
            else -> songs
        }
    }

    fun refresh() {
        allSongs = MusicRepository.loadAllSongs(requireContext())
        adapter.updateData(applySort(allSongs, currentSortId))
    }
}
