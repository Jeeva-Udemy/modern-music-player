package com.claude.musicplayer

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton

class PlaylistSongsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
    }

    private lateinit var playlistName: String
    private lateinit var adapter: SongAdapter
    private lateinit var normalHeader: View
    private lateinit var selectionHeader: View
    private lateinit var selectionCountText: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_songs) // same layout shape, reused

        playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: ""

        normalHeader = findViewById(R.id.normalHeader)
        selectionHeader = findViewById(R.id.selectionHeader)
        selectionCountText = findViewById(R.id.selectionCountText)
        actionButton = findViewById(R.id.actionSelectedButton)
        swipeRefresh = findViewById(R.id.folderSwipeRefresh)

        findViewById<TextView>(R.id.folderTitle).text = playlistName
        findViewById<ImageButton>(R.id.folderBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.selectModeButton).setOnClickListener { enterSelectionMode() }
        findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        actionButton.text = "Remove"
        actionButton.setOnClickListener { confirmRemoveSelected() }

        val recyclerView = findViewById<RecyclerView>(R.id.folderSongsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SongAdapter(
            loadSongs(),
            onClick = { position ->
                // Play starting from whichever song was tapped, not always from the top.
                PlaybackController.playAndOpenNowPlaying(this, adapter.currentList(), position)
            },
            onLongClick = { position -> confirmRemoveSingle(adapter.currentList()[position]) },
            isSelectionMode = { selectionMode },
            isSelected = { song -> selectedPaths.contains(song.path) },
            onToggleSelect = { song ->
                if (!selectedPaths.remove(song.path)) selectedPaths.add(song.path)
                updateSelectionUi()
            },
            nowPlayingPath = { MusicService.nowPlayingPath },
            nowPlayingActive = { MusicService.nowPlayingIsActive }
        )
        recyclerView.adapter = adapter

        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            adapter.updateData(loadSongs())
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.updateData(loadSongs())
    }

    private fun loadSongs(): List<Song> {
        val allSongs = MusicRepository.loadAllSongs(this)
        return PlaylistManager.getSongsInPlaylist(this, playlistName, allSongs)
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedPaths.clear()
        normalHeader.visibility = View.GONE
        selectionHeader.visibility = View.VISIBLE
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        normalHeader.visibility = View.VISIBLE
        selectionHeader.visibility = View.GONE
        adapter.refreshSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionCountText.text = "${selectedPaths.size} selected"
        actionButton.isEnabled = selectedPaths.isNotEmpty()
        adapter.refreshSelectionUi()
    }

    private fun confirmRemoveSingle(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Remove from playlist?")
            .setMessage("Remove \"${song.title}\" from \"$playlistName\"? The file itself won't be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                PlaylistManager.removeSongFromPlaylist(this, playlistName, song.path)
                adapter.updateData(loadSongs())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveSelected() {
        if (selectedPaths.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Remove ${selectedPaths.size} song(s)?")
            .setMessage("They'll be removed from \"$playlistName\". The files themselves won't be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                for (path in selectedPaths.toList()) {
                    PlaylistManager.removeSongFromPlaylist(this, playlistName, path)
                }
                Toast.makeText(this, "Removed ${selectedPaths.size} song(s).", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                adapter.updateData(loadSongs())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
