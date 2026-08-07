package com.claude.musicplayer

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast

object PlaylistDialogHelper {

    fun showAddToPlaylistDialog(context: Context, song: Song) {
        val existing = PlaylistManager.getPlaylistNames(context)
        val options = (existing + "+ New playlist").toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Add \"${song.title}\" to playlist")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    promptNewPlaylistAndAdd(context, song)
                } else {
                    val name = options[which]
                    PlaylistManager.addSongToPlaylist(context, name, song.path)
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewPlaylistAndAdd(context: Context, song: Song) {
        val input = EditText(context)
        AlertDialog.Builder(context)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    PlaylistManager.createPlaylist(context, name)
                    PlaylistManager.addSongToPlaylist(context, name, song.path)
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
