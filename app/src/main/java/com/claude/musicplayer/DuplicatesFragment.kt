package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DuplicatesFragment : Fragment(R.layout.fragment_duplicates) {

    private lateinit var adapter: DuplicateAdapter
    private lateinit var deleteButton: Button
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar

    private var groups: List<DuplicateFinder.DuplicateGroup> = emptyList()
    private val selectedPaths = mutableSetOf<String>()

    // Set when the user has to go grant "All files access" before deleting
    // can proceed — resumes automatically once they're back, same pattern
    // as the Move-to-Music-folder flow.
    private var pendingDeleteAfterPermission = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.duplicatesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        deleteButton = view.findViewById(R.id.deleteDuplicatesButton)
        emptyView = view.findViewById(R.id.duplicatesEmptyView)
        progressBar = view.findViewById(R.id.duplicatesProgressBar)

        adapter = DuplicateAdapter(
            rows = emptyList(),
            isSelected = { song -> selectedPaths.contains(song.path) },
            onToggle = { song ->
                if (!selectedPaths.remove(song.path)) selectedPaths.add(song.path)
                updateDeleteButtonLabel()
            }
        )
        recyclerView.adapter = adapter

        deleteButton.setOnClickListener { confirmDelete() }

        loadDuplicates()
    }

    override fun onResume() {
        super.onResume()
        if (pendingDeleteAfterPermission && FileOrganizer.hasFullStorageAccess()) {
            pendingDeleteAfterPermission = false
            performDelete(selectedPaths.toList())
        } else if (!pendingDeleteAfterPermission) {
            loadDuplicates()
        }
    }

    private fun loadDuplicates() {
        val allSongs = MusicRepository.loadAllSongs(requireContext())
        groups = DuplicateFinder.findDuplicates(allSongs)

        // Drop any selections for songs that no longer exist in a duplicate group.
        val stillValidPaths = groups.flatMap { it.songs }.map { it.path }.toSet()
        selectedPaths.retainAll(stillValidPaths)

        val rows = mutableListOf<DuplicateRow>()
        groups.forEach { group ->
            val first = group.songs.first()
            rows.add(DuplicateRow.Header(first.title, first.artist, group.songs.size))
            group.songs.forEach { rows.add(DuplicateRow.SongRow(it)) }
        }
        adapter.updateData(rows)

        emptyView.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        updateDeleteButtonLabel()
    }

    private fun updateDeleteButtonLabel() {
        deleteButton.isEnabled = selectedPaths.isNotEmpty()
        deleteButton.text = if (selectedPaths.isNotEmpty()) {
            "Delete Selected (${selectedPaths.size})"
        } else {
            "Delete Selected"
        }
    }

    private fun confirmDelete() {
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
        progressBar.visibility = View.VISIBLE
        deleteButton.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (path in paths) {
                    if (FileOrganizer.deleteFile(requireContext(), path)) count++
                }
                count
            }
            progressBar.visibility = View.GONE
            selectedPaths.clear()
            Toast.makeText(requireContext(), "Deleted $deletedCount file(s).", Toast.LENGTH_LONG).show()
            loadDuplicates()
        }
    }
}
