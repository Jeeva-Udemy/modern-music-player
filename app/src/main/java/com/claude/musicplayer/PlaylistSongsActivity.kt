package com.claude.musicplayer

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistSongsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
    }

    private lateinit var playlistName: String
    private lateinit var adapter: SongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_songs) // same layout shape, reused

        playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: ""

        findViewById<TextView>(R.id.folderTitle).text = playlistName
        findViewById<ImageButton>(R.id.folderBack).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.folderSongsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SongAdapter(
            loadSongs(),
            onClick = { position ->
                // Play starting from whichever song was tapped, not always from the top.
                PlaybackController.playAndOpenNowPlaying(this, adapter.currentList(), position)
            },
            onLongClick = { position -> confirmRemove(adapter.currentList()[position]) }
        )
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.updateData(loadSongs())
    }

    private fun loadSongs(): List<Song> {
        val allSongs = MusicRepository.loadAllSongs(this)
        return PlaylistManager.getSongsInPlaylist(this, playlistName, allSongs)
    }

    private fun confirmRemove(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Remove from playlist?")
            .setMessage("Remove \"${song.title}\" from \"$playlistName\"?")
            .setPositiveButton("Remove") { _, _ ->
                PlaylistManager.removeSongFromPlaylist(this, playlistName, song.path)
                adapter.updateData(loadSongs())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
