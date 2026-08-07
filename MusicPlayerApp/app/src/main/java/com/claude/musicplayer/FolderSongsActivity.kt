package com.claude.musicplayer

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FolderSongsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_songs)

        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""

        // Recomputed here instead of being passed through the Intent —
        // large folders (1000+ tracks) would otherwise exceed Android's
        // Binder transaction size limit and crash on open.
        val songs = MusicRepository.loadFolders(this)[folderPath] ?: emptyList()

        findViewById<TextView>(R.id.folderTitle).text = File(folderPath).name
        findViewById<ImageButton>(R.id.folderBack).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.folderSongsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SongAdapter(
            songs,
            onClick = { position -> PlaybackController.playAndOpenNowPlaying(this, songs, position) },
            onLongClick = { position -> PlaylistDialogHelper.showAddToPlaylistDialog(this, songs[position]) }
        )
    }
}
