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
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single reusable song-list screen: search, sort, tap-to-play, multi-select
 * with bulk actions, a per-row "more" menu, and live now-playing highlight.
 *
 * Used two ways from the same code, via the factory functions below:
 *  - [forAllSongs] — the Songs tab (bulk/row action is "Delete from device").
 *  - [forPlaylist] — a playlist's contents (bulk/row action is "Remove from
 *    playlist", with "Delete from device" also offered per-row for parity).
 */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    companion object {
        private const val ARG_PLAYLIST_NAME = "arg_playlist_name"

        fun forAllSongs(): SongsFragment = SongsFragment()

        fun forPlaylist(playlistName: String): SongsFragment = SongsFragment().apply {
            arguments = Bundle().apply { putString(ARG_PLAYLIST_NAME, playlistName) }
        }
    }

    private val playlistName: String? get() = arguments?.getString(ARG_PLAYLIST_NAME)
    private val isPlaylistMode: Boolean get() = playlistName != null

    private var allSongs: List<Song> = emptyList()
    private lateinit var adapter: SongAdapter
    private var currentSortId: Int = R.id.sort_title_asc
    private var searchQuery: String = ""

    private lateinit var normalHeader: View
    private lateinit var selectionHeader: View
    private lateinit var selectionCountText: TextView
    private lateinit var bulkActionButton: TextView
    private lateinit var addSelectedToPlaylistButton: ImageButton
    private lateinit var searchEditText: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()
    private var pendingDeleteAfterPermission = false

    // Pressing system Back while selecting should exit selection mode
    // first, not close the whole screen/app.
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

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

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        normalHeader = view.findViewById(R.id.normalHeader)
        selectionHeader = view.findViewById(R.id.selectionHeader)
        selectionCountText = view.findViewById(R.id.selectionCountText)
        bulkActionButton = view.findViewById(R.id.deleteSelectedButton)
        addSelectedToPlaylistButton = view.findViewById(R.id.addSelectedToPlaylistButton)
        searchEditText = view.findViewById(R.id.searchEditText)
        swipeRefresh = view.findViewById(R.id.songsSwipeRefresh)

        view.findViewById<TextView>(R.id.headerTitle).text = playlistName ?: "Your Music"
        val backButton = view.findViewById<ImageButton>(R.id.songsBackButton)
        if (isPlaylistMode) {
            backButton.visibility = View.VISIBLE
            backButton.setOnClickListener { requireActivity().finish() }
        } else {
            backButton.visibility = View.GONE
        }

        bulkActionButton.text = if (isPlaylistMode) "Remove" else "Delete"
        currentSortId = if (isPlaylistMode) R.id.sort_playlist_order else SortPreference.load(requireContext())

        adapter = SongAdapter(
            songs = emptyList(),
            onClick = { position ->
                // Tapping a song just plays it — the full player only opens
                // from tapping the mini player bar. Same behavior for the
                // Songs tab and inside a playlist.
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
        bulkActionButton.setOnClickListener { confirmBulkAction() }
        addSelectedToPlaylistButton.setOnClickListener {
            val songs = adapter.currentList().filter { it.path in selectedPaths }
            if (songs.isNotEmpty()) {
                PlaylistDialogHelper.showAddToPlaylistDialog(requireContext(), songs)
            }
        }

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
            performDeleteFromDevice(selectedPaths.toList())
        } else if (!pendingDeleteAfterPermission) {
            refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(nowPlayingPoller)
    }

    private fun loadSongsSource(): List<Song> {
        val library = MusicRepository.loadAllSongs(requireContext())
        return if (isPlaylistMode) {
            val name = playlistName ?: return emptyList()
            PlaylistManager.getSongsInPlaylist(requireContext(), name, library)
        } else {
            library
        }
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
        backPressedCallback.isEnabled = true
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        normalHeader.visibility = View.VISIBLE
        selectionHeader.visibility = View.GONE
        backPressedCallback.isEnabled = false
        adapter.refreshSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionCountText.text = "${selectedPaths.size} selected"
        bulkActionButton.isEnabled = selectedPaths.isNotEmpty()
        addSelectedToPlaylistButton.isEnabled = selectedPaths.isNotEmpty()
        adapter.refreshSelectionUi()
    }

    private fun showRowMenu(song: Song, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        if (isPlaylistMode) {
            popup.menu.add("Add to Another Playlist")
            popup.menu.add("Remove from this Playlist")
            popup.menu.add("Set as Ringtone")
            popup.menu.add("Delete from Device")
        } else {
            popup.menu.add("Add to Playlist")
            popup.menu.add("Set as Ringtone")
            popup.menu.add("Delete")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Add to Playlist", "Add to Another Playlist" ->
                    PlaylistDialogHelper.showAddToPlaylistDialog(requireContext(), song)
                "Set as Ringtone" -> RingtoneHelper.setAsRingtone(requireActivity(), song)
                "Remove from this Playlist" -> confirmRemoveSingleFromPlaylist(song)
                "Delete", "Delete from Device" -> confirmDeleteSingleFromDevice(song)
            }
            true
        }
        popup.show()
    }

    private fun confirmRemoveSingleFromPlaylist(song: Song) {
        val name = playlistName ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Remove from playlist?")
            .setMessage("Remove \"${song.title}\" from \"$name\"? The file itself won't be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                PlaylistManager.removeSongFromPlaylist(requireContext(), name, song.path)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSingleFromDevice(song: Song) {
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
                performDeleteFromDevice(listOf(song.path))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmBulkAction() {
        if (selectedPaths.isEmpty()) return
        if (isPlaylistMode) {
            val name = playlistName ?: return
            AlertDialog.Builder(requireContext())
                .setTitle("Remove ${selectedPaths.size} song(s)?")
                .setMessage("They'll be removed from \"$name\". The files themselves won't be deleted.")
                .setPositiveButton("Remove") { _, _ -> performRemoveFromPlaylist(selectedPaths.toList()) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete ${selectedPaths.size} file(s)?")
                .setMessage("These files will be permanently deleted from your device. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    if (!FileOrganizer.hasFullStorageAccess()) {
                        pendingDeleteAfterPermission = true
                        requestAllFilesAccess()
                        return@setPositiveButton
                    }
                    performDeleteFromDevice(selectedPaths.toList())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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

    private fun performRemoveFromPlaylist(paths: List<String>) {
        val name = playlistName ?: return
        for (path in paths) {
            PlaylistManager.removeSongFromPlaylist(requireContext(), name, path)
        }
        Toast.makeText(requireContext(), "Removed ${paths.size} song(s).", Toast.LENGTH_SHORT).show()
        if (selectionMode) exitSelectionMode()
        refresh()
    }

    private fun performDeleteFromDevice(paths: List<String>) {
        CoroutineScope(Dispatchers.Main).launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (path in paths) {
                    if (FileOrganizer.deleteFile(requireContext(), path)) {
                        count++
                        // Don't leave a dangling reference to a file that no longer exists.
                        if (isPlaylistMode) {
                            playlistName?.let { PlaylistManager.removeSongFromPlaylist(requireContext(), it, path) }
                        }
                        PlaybackController.notifySongRemoved(requireContext(), path)
                    }
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
        val menuRes = if (isPlaylistMode) R.menu.sort_menu_playlist else R.menu.sort_menu
        popup.menuInflater.inflate(menuRes, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            currentSortId = item.itemId
            if (!isPlaylistMode) SortPreference.save(requireContext(), currentSortId)
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
            else -> songs // R.id.sort_playlist_order (or unknown) — keep insertion order as-is
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
        allSongs = loadSongsSource()
        val stillValidPaths = allSongs.map { it.path }.toSet()
        selectedPaths.retainAll(stillValidPaths)
        applyFiltersAndSort()
        if (selectionMode) updateSelectionUi()
    }
}
