package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playlists shown as a two-column grid of cover-art cards. */
class PlaylistFragment : Fragment(R.layout.fragment_playlist) {

    private lateinit var adapter: PlaylistCardAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyView: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.playlistRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = PlaylistCardAdapter(
            onOpen = { info -> openPlaylist(info) },
            onPlay = { info ->
                if (info.songs.isNotEmpty()) PlaybackController.play(requireContext(), info.songs, 0)
            },
            onMore = { info, anchor -> showCardMenu(info, anchor) }
        )
        recyclerView.adapter = adapter
        emptyView = view.findViewById(R.id.playlistEmptyView)

        view.findViewById<View>(R.id.addPlaylistButton).setOnClickListener {
            showCreatePlaylistDialog()
        }

        swipeRefresh = view.findViewById(R.id.playlistSwipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener { refresh() }
    }

    private fun showCardMenu(info: PlaylistCardInfo, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("Open")
        if (info.songs.isNotEmpty()) popup.menu.add("Play")
        popup.menu.add("Delete playlist")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Open" -> openPlaylist(info)
                "Play" -> PlaybackController.play(requireContext(), info.songs, 0)
                "Delete playlist" -> confirmDelete(info)
            }
            true
        }
        popup.show()
    }

    private fun openPlaylist(info: PlaylistCardInfo) {
        startActivity(Intent(requireContext(), PlaylistSongsActivity::class.java).apply {
            putExtra(PlaylistSongsActivity.EXTRA_PLAYLIST_NAME, info.name)
        })
    }

    private fun confirmDelete(info: PlaylistCardInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete \"${info.name}\"?")
            .setMessage("Only the playlist is removed — the songs stay on your device.")
            .setPositiveButton("Delete") { _, _ ->
                PlaylistManager.deletePlaylist(requireContext(), info.name)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    PlaylistManager.createPlaylist(requireContext(), name)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refresh() {
        val context = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                val names = PlaylistManager.getPlaylistNames(context)
                val library = if (names.isEmpty()) emptyList() else MusicRepository.loadAllSongs(context)
                names.map { name ->
                    val songs = PlaylistManager.getSongsInPlaylist(context, name, library)
                    // First few songs only — decoding is cached, so the card bind is instant.
                    val cover = songs.asSequence()
                        .mapNotNull { it.albumArtUri }
                        .distinct()
                        .take(5)
                        .firstOrNull { MusicRepository.loadAlbumArt(context, it, 400) != null }
                    PlaylistCardInfo(name, songs, cover)
                }
            }
            if (view == null) return@launch
            adapter.submit(cards)
            emptyView.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
            swipeRefresh.isRefreshing = false
            view?.findViewById<View>(R.id.portabilityHint)?.visibility =
                if (PlaylistManager.isPortable()) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
