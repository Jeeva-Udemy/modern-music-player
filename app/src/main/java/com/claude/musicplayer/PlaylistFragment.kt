package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class PlaylistFragment : Fragment(R.layout.fragment_playlist) {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView = view.findViewById(R.id.playlistListView)
        adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            PlaylistManager.getPlaylistNames(requireContext()).toMutableList()
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val name = adapter.getItem(position) ?: return@setOnItemClickListener
            val intent = Intent(requireContext(), PlaylistSongsActivity::class.java).apply {
                putExtra(PlaylistSongsActivity.EXTRA_PLAYLIST_NAME, name)
            }
            startActivity(intent)
        }

        view.findViewById<View>(R.id.addPlaylistButton).setOnClickListener {
            showCreatePlaylistDialog()
        }

        swipeRefresh = view.findViewById(R.id.playlistSwipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            refresh()
            swipeRefresh.isRefreshing = false
        }
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
        adapter.clear()
        adapter.addAll(PlaylistManager.getPlaylistNames(requireContext()))
        adapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
