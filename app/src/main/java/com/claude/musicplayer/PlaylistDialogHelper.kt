package com.claude.musicplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object PlaylistDialogHelper {

    /** Single-song convenience — same dialog, just wraps it in a one-item list. */
    fun showAddToPlaylistDialog(context: Context, song: Song) {
        showAddToPlaylistDialog(context, listOf(song))
    }

    /** Adds every song in [songs] to whichever playlist the user picks (or a new one). */
    fun showAddToPlaylistDialog(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val existing = PlaylistManager.getPlaylistNames(context)
        val options = (existing + "+ New playlist").toTypedArray()
        val label = if (songs.size == 1) "\"${songs[0].title}\"" else "${songs.size} songs"

        MaterialAlertDialogBuilder(context)
            .setTitle("Add $label to playlist")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    showCreatePlaylistDialog(context) { name ->
                        songs.forEach { PlaylistManager.addSongToPlaylist(context, name, it.path) }
                        Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val name = options[which]
                    songs.forEach { PlaylistManager.addSongToPlaylist(context, name, it.path) }
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for a name and creates the playlist; [onCreated] gets the final name. */
    fun showCreatePlaylistDialog(context: Context, onCreated: (String) -> Unit = {}) {
        showNameDialog(
            context, title = "New playlist", initial = "", confirmLabel = "Create",
            validate = { name ->
                if (PlaylistManager.getPlaylistNames(context).any { it.equals(name, ignoreCase = true) })
                    "A playlist with this name already exists" else null
            }
        ) { name ->
            PlaylistManager.createPlaylist(context, name)
            onCreated(name)
        }
    }

    /** Asks for a new name for [oldName]; [onRenamed] gets the new name once saved. */
    fun showRenameDialog(context: Context, oldName: String, onRenamed: (String) -> Unit) {
        showNameDialog(
            context, title = "Rename playlist", initial = oldName, confirmLabel = "Rename",
            validate = { null }
        ) { newName ->
            when (PlaylistManager.renamePlaylist(context, oldName, newName)) {
                PlaylistManager.RenameResult.Ok -> {
                    if (newName != oldName) {
                        Toast.makeText(context, "Renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
                    }
                    onRenamed(newName)
                }
                PlaylistManager.RenameResult.AlreadyExists ->
                    Toast.makeText(context, "A playlist named \"$newName\" already exists", Toast.LENGTH_LONG).show()
                PlaylistManager.RenameResult.Blank -> Unit
            }
        }
    }

    /**
     * Glass-styled name prompt shared by "New playlist" and "Rename". Errors
     * show inline under the field (the dialog stays open) instead of the old
     * behaviour of silently doing nothing.
     */
    private fun showNameDialog(
        context: Context,
        title: String,
        initial: String,
        confirmLabel: String,
        validate: (String) -> String?,
        onConfirm: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_name_input, null)
        val layout = view.findViewById<TextInputLayout>(R.id.nameInputLayout)
        val input = view.findViewById<TextInputEditText>(R.id.nameInput)
        input.setText(initial)
        input.setSelection(initial.length)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(confirmLabel, null) // wired below so errors keep the dialog open
            .setNegativeButton("Cancel", null)
            .create()

        fun trySubmit() {
            val name = input.text?.toString()?.trim().orEmpty()
            val error = when {
                name.isEmpty() -> "Enter a name"
                // These characters can't be used in file names, and every
                // playlist is also saved as a file in Music/Playlists.
                name.any { it in "\\/:*?\"<>|" } -> "Name can't contain \\ / : * ? \" < > |"
                else -> validate(name)
            }
            if (error != null) {
                layout.error = error
                return
            }
            dialog.dismiss()
            onConfirm(name)
        }

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { trySubmit() }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { trySubmit(); true } else false
        }
        input.doOnTextChangedClearError(layout)
        dialog.show()
    }

    private fun TextInputEditText.doOnTextChangedClearError(layout: TextInputLayout) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                layout.error = null
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
}
