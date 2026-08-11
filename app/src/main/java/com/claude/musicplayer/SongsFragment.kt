package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongsFragment : Fragment(R.layout.fragment_songs) {

    private var allSongs: List<Song> = emptyList()
    private lateinit var adapter: SongAdapter
    private var currentSortId: Int = R.id.sort_title_asc
    private var searchQuery: String = ""

    private lateinit var normalHeader: View
    private lateinit var selectionHeader: View
    private lateinit var selectionCountText: TextView
    private lateinit var deleteButton: View
    private lateinit var searchEditText: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.songsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        normalHeader = view.findViewById(R.id.normalHeader)
        selectionHeader = view.findViewById(R.id.selectionHeader)
        selectionCountText = view.findViewById(R.id.selectionCountText)
        deleteButton = view.findViewById(R.id.deleteSelectedButton)
        searchEditText = view.findViewById(R.id.searchEditText)
        swipeRefresh = view.findViewById(R.id.songsSwipeRefresh)

        currentSortId = SortPreference.load(requireContext())

        adapter = SongAdapter(
            songs = emptyList(),
            onClick = { position ->
                // Tapping a song just plays it now — the full player only
                // opens from tapping the mini player bar at the bottom.
                PlaybackController.play(requireContext(), adapter.currentList(), position)
            },
            onLongClick = { position ->
                PlaylistDialogHelper.showAddToPlaylistDialog(requireContext(), adapter.currentList()[position])
            },
            onMoreClick = { position, anchor -> showRowMenu(adapter.currentList()[position], anchor) },
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

        view.findViewById<TextView>(R.id.sortButton).setOnClickListener { anchor -> showSortMenu(anchor) }

        view.findViewById<ImageButton>(R.id.searchToggleButton).setOnClickListener { toggleSearch() }
        view.findViewById<ImageButton>(R.id.selectModeButton).setOnClickListener { enterSelectionMode() }
        view.findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        deleteButton.setOnClickListener { confirmDeleteSelected() }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                applyFiltersAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            refresh()
            swipeRefresh.isRefreshing = false
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(nowPlayingPoller)
        if (pendingDeleteAfterPermission && FileOrganizer.hasFullStorageAccess()) {
            pendingDeleteAfterPermission = false
            performDelete(selectedPaths.toList())
        } else if (!pendingDeleteAfterPermission) {
            refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(nowPlayingPoller)
    }

    private fun toggleSearch() {
        val show = searchEditText.visibility != View.VISIBLE
        searchEditText.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            searchEditText.setText("")
            searchQuery = ""
            applyFiltersAndSort()
        }
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
        deleteButton.isEnabled = selectedPaths.isNotEmpty()
        adapter.refreshSelectionUi()
    }

    private fun showRowMenu(song: Song, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("Add to Playlist")
        popup.menu.add("Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Add to Playlist" -> PlaylistDialogHelper.showAddToPlaylistDialog(requireContext(), song)
                "Delete" -> confirmDeleteSingle(song)
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteSingle(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete \"${song.title}\"?")
            .setMessage("This permanently deletes the file from your device. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (!FileOrganizer.hasFullStorageAccess()) {
                    selectedPaths.clear()
                    selectedPaths.add(song.path)
                    pendingDeleteAfterPermission = true
                    requestAllFilesAccess()
                    return@setPositiveButton
                }
                performDelete(listOf(song.path))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSelected() {
        if (selectedPaths.isEmpty()) return
        AlertDialog.Builder(requireContext())
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
                requireContext(),
                "Grant \"All files access\" for Music Player, then come back — deletion will continue automatically.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        }
    }

    private fun performDelete(paths: List<String>) {
        CoroutineScope(Dispatchers.Main).launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (path in paths) {
                    if (FileOrganizer.deleteFile(requireContext(), path)) count++
                }
                count
            }
            Toast.makeText(requireContext(), "Deleted $deletedCount file(s).", Toast.LENGTH_LONG).show()
            if (selectionMode) exitSelectionMode()
            refresh()
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.sort_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            currentSortId = item.itemId
            SortPreference.save(requireContext(), currentSortId)
            applyFiltersAndSort()
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

    private fun applyFiltersAndSort() {
        val filtered = if (searchQuery.isBlank()) {
            allSongs
        } else {
            val q = searchQuery.trim().lowercase()
            allSongs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        }
        adapter.updateData(applySort(filtered, currentSortId))
    }

    fun refresh() {
        allSongs = MusicRepository.loadAllSongs(requireContext())
        // Selections for songs that no longer exist (e.g. just deleted) are dropped.
        val stillValidPaths = allSongs.map { it.path }.toSet()
        selectedPaths.retainAll(stillValidPaths)
        applyFiltersAndSort()
        if (selectionMode) updateSelectionUi()
    }
}
