package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FolderSongsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }

    private lateinit var folderPath: String
    private lateinit var adapter: SongAdapter
    private lateinit var normalHeader: View
    private lateinit var selectionHeader: View
    private lateinit var selectionCountText: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var miniPlayer: MiniPlayerController

    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()
    private var pendingDeleteAfterPermission = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastNowPlayingPath: String? = null
    private var lastNowPlayingActive: Boolean = false
    private val nowPlayingPoller = object : Runnable {
        override fun run() {
            val path = MusicService.nowPlayingPath
            val active = MusicService.nowPlayingIsActive
            if (path != lastNowPlayingPath || active != lastNowPlayingActive) {
                lastNowPlayingPath = path
                lastNowPlayingActive = active
                adapter.refreshNowPlaying()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_songs)

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""

        normalHeader = findViewById(R.id.normalHeader)
        selectionHeader = findViewById(R.id.selectionHeader)
        selectionCountText = findViewById(R.id.selectionCountText)
        actionButton = findViewById(R.id.actionSelectedButton)
        swipeRefresh = findViewById(R.id.folderSwipeRefresh)

        findViewById<TextView>(R.id.folderTitle).text = File(folderPath).name
        findViewById<ImageButton>(R.id.folderBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.selectModeButton).setOnClickListener { enterSelectionMode() }
        findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        actionButton.text = "Delete"
        actionButton.setOnClickListener { confirmDelete() }

        val recyclerView = findViewById<RecyclerView>(R.id.folderSongsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SongAdapter(
            loadSongs(),
            onClick = { position ->
                PlaybackController.playAndOpenNowPlaying(this, adapter.currentList(), position)
            },
            onLongClick = { position ->
                PlaylistDialogHelper.showAddToPlaylistDialog(this, adapter.currentList()[position])
            },
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

        miniPlayer = MiniPlayerController(
            context = this,
            miniPlayerBar = findViewById(R.id.miniPlayerBar),
            titleView = findViewById(R.id.nowPlayingTitle),
            artistView = findViewById(R.id.nowPlayingArtist),
            artView = findViewById<ImageView>(R.id.miniArt),
            playPauseButton = findViewById(R.id.playPauseButton),
            prevButton = findViewById(R.id.prevButton),
            nextButton = findViewById(R.id.nextButton),
            seekBar = findViewById<SeekBar>(R.id.miniSeekBar)
        )
        miniPlayer.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(nowPlayingPoller)
        if (pendingDeleteAfterPermission && FileOrganizer.hasFullStorageAccess()) {
            pendingDeleteAfterPermission = false
            performDelete(selectedPaths.toList())
        } else if (!pendingDeleteAfterPermission) {
            adapter.updateData(loadSongs())
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(nowPlayingPoller)
    }

    override fun onDestroy() {
        miniPlayer.stop()
        super.onDestroy()
    }

    private fun loadSongs(): List<Song> = MusicRepository.loadFolders(this)[folderPath] ?: emptyList()

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

    private fun confirmDelete() {
        if (selectedPaths.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedPaths.size} file(s)?")
            .setMessage("These files will be permanently deleted from your device. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (!FileOrganizer.hasFullStorageAccess()) {
                    pendingDeleteAfterPermission = true
                    requestAllFilesAccess()
                    return@setPositiveButton
                }
                performDelete(selectedPaths.toList())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(
                this,
                "Grant \"All files access\" for Music Player, then come back — deletion will continue automatically.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun performDelete(paths: List<String>) {
        CoroutineScope(Dispatchers.Main).launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (path in paths) {
                    if (FileOrganizer.deleteFile(this@FolderSongsActivity, path)) {
                        count++
                        PlaybackController.notifySongRemoved(this@FolderSongsActivity, path)
                    }
                }
                count
            }
            Toast.makeText(this@FolderSongsActivity, "Deleted $deletedCount file(s).", Toast.LENGTH_LONG).show()
            if (selectionMode) exitSelectionMode()
            adapter.updateData(loadSongs())
        }
    }
}
