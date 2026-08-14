package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast

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

        AlertDialog.Builder(context)
            .setTitle("Add $label to playlist")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    promptNewPlaylistAndAdd(context, songs)
                } else {
                    val name = options[which]
                    songs.forEach { PlaylistManager.addSongToPlaylist(context, name, it.path) }
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewPlaylistAndAdd(context: Context, songs: List<Song>) {
        val input = EditText(context)
        AlertDialog.Builder(context)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    PlaylistManager.createPlaylist(context, name)
                    songs.forEach { PlaylistManager.addSongToPlaylist(context, name, it.path) }
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
