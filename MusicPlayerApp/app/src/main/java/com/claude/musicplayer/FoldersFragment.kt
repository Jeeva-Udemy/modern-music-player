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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FoldersFragment : Fragment(R.layout.fragment_folders) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FolderAdapter
    private lateinit var moveButton: Button
    private lateinit var progressBar: ProgressBar

    // Set when the user taps Move but has to go grant "All files access"
    // first — so we can resume the move automatically once they're back,
    // instead of requiring them to notice and tap Move a second time.
    private var pendingMoveAfterPermission = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.foldersRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = FolderAdapter(MusicRepository.loadFolders(requireContext()).entries.toList()) { path, _ ->
            val intent = Intent(requireContext(), FolderSongsActivity::class.java).apply {
                putExtra(FolderSongsActivity.EXTRA_FOLDER_PATH, path)
            }
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        moveButton = view.findViewById(R.id.moveMp3sButton)
        progressBar = view.findViewById(R.id.moveProgressBar)

        moveButton.setOnClickListener { confirmAndMoveFiles() }
    }

    override fun onResume() {
        super.onResume()
        if (pendingMoveAfterPermission && FileOrganizer.hasFullStorageAccess()) {
            pendingMoveAfterPermission = false
            runMove()
        } else if (!pendingMoveAfterPermission) {
            adapter.updateData(MusicRepository.loadFolders(requireContext()))
        }
    }

    private fun confirmAndMoveFiles() {
        AlertDialog.Builder(requireContext())
            .setTitle("Move all MP3s to Music folder?")
            .setMessage(
                "This finds every .mp3 file on your device's storage and moves " +
                        "it into a single \"Music\" folder. This cannot be undone."
            )
            .setPositiveButton("Move") { _, _ ->
                if (!FileOrganizer.hasFullStorageAccess()) {
                    pendingMoveAfterPermission = true
                    requestAllFilesAccess()
                    return@setPositiveButton
                }
                runMove()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(
                requireContext(),
                "Grant \"All files access\" for Music Player, then come back — " +
                        "the move will continue automatically.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        }
    }

    private fun runMove() {
        moveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    FileOrganizer.moveAllMp3sToMusicFolder(requireContext())
                }
            } catch (e: Exception) {
                null
            }

            progressBar.visibility = View.GONE
            moveButton.isEnabled = true

            if (result == null) {
                Toast.makeText(
                    requireContext(),
                    "Something went wrong while moving files. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            Toast.makeText(
                requireContext(),
                "Moved ${result.moved} file(s) to ${result.targetFolder}. " +
                        "${result.skipped} already there, ${result.failed} failed.",
                Toast.LENGTH_LONG
            ).show()

            // Refresh the folder list to reflect the new layout.
            val refreshed = MusicRepository.loadFolders(requireContext())
            adapter.updateData(refreshed)
        }
    }
}
