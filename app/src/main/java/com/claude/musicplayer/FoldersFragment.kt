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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FoldersFragment : Fragment(R.layout.fragment_folders) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FolderAdapter
    private lateinit var moveButton: Button
    private lateinit var importButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // Set when the user taps Move but has to go grant "All files access"
    // first — so we can resume the move automatically once they're back,
    // instead of requiring them to notice and tap Move a second time.
    private var pendingMoveAfterPermission = false

    // Storage Access Framework picker for importing from folders regular
    // file APIs can't reach (e.g. another app's Android/media directory).
    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) runImport(uri)
    }

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
        importButton = view.findViewById(R.id.importFolderButton)
        progressBar = view.findViewById(R.id.moveProgressBar)

        moveButton.setOnClickListener { confirmAndMoveFiles() }
        importButton.setOnClickListener { confirmAndImportFolder() }

        swipeRefresh = view.findViewById(R.id.foldersSwipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            adapter.updateData(MusicRepository.loadFolders(requireContext()))
            swipeRefresh.isRefreshing = false
        }
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
            .setTitle("Move all audio files to Music folder?")
            .setMessage(
                "This finds every supported audio file (MP3, M4A, AAC, WAV, FLAC, " +
                        "OGG, Opus, WMA, and more) on your device's storage and moves " +
                        "it into a single \"Music\" folder. This cannot be undone.\n\n" +
                        "Note: it can't reach folders that belong to other apps (like " +
                        "WhatsApp's voice notes) — use \"Import from another app's " +
                        "folder\" below for those."
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

    private fun confirmAndImportFolder() {
        AlertDialog.Builder(requireContext())
            .setTitle("Import audio from another app's folder?")
            .setMessage(
                "Folders that belong to other apps (like WhatsApp's " +
                        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio) are " +
                        "protected by Android and can't be read by the Move button above " +
                        "— that's an OS restriction, not something any app can bypass on " +
                        "its own. Picking the folder yourself in the next screen and " +
                        "granting access to it is the one way around that.\n\n" +
                        "Browse to and select the folder, then tap \"Use this folder\"."
            )
            .setPositiveButton("Choose Folder") { _, _ -> importFolderLauncher.launch(null) }
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
                    FileOrganizer.moveAllAudioFilesToMusicFolder(requireContext())
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

    private fun runImport(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some providers don't support persistable permissions — the
            // one-time copy below still works even if this fails.
        }

        importButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val count = try {
                withContext(Dispatchers.IO) {
                    SafImporter.importAudioFrom(requireContext(), treeUri)
                }
            } catch (e: Exception) {
                -1
            }

            progressBar.visibility = View.GONE
            importButton.isEnabled = true

            if (count < 0) {
                Toast.makeText(requireContext(), "Something went wrong importing that folder.", Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(requireContext(), "Imported $count audio file(s) to the Music folder.", Toast.LENGTH_LONG).show()
            adapter.updateData(MusicRepository.loadFolders(requireContext()))
        }
    }
}
