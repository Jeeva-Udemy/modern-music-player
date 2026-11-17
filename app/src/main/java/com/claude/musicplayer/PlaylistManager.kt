package com.claude.musicplayer

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Playlist storage backed by real .m3u8 files on shared storage (under
 * Music/Playlists/), not just this app's private data. Two things that
 * unlocks:
 *
 * 1. Playlists survive uninstalling and reinstalling the app — they live as
 *    ordinary files on the device, not inside the app's sandboxed data
 *    (which is wiped on uninstall). [syncFromDisk] re-imports them
 *    automatically the next time the Playlist tab is opened.
 * 2. M3U is the closest thing to a universal playlist format — nearly every
 *    music app (VLC, Poweramp, foobar2000, iTunes, etc.) can read files in
 *    this format, and this app can in turn read playlists any of them
 *    dropped into that same folder.
 *
 * A SharedPreferences copy is the working copy and the fallback for devices
 * that haven't granted "All files access" (file writes are best-effort
 * without it).
 *
 * Without "All files access" Android still lets the app *see* playlist files
 * it can't change or delete (e.g. ones written by a previous install). Two
 * rules keep those from undoing the user's edits:
 *  - Local edits win: a file is only re-imported when it changed after the
 *    app's own last edit of that playlist (i.e. another app edited it).
 *  - Renamed/deleted playlists whose old file can't be removed are hidden
 *    from import — previously a rename re-imported the old file next to the
 *    renamed playlist, so it looked like a copy had been created. Hidden
 *    files are cleaned up for real once "All files access" is granted.
 */
object PlaylistManager {
    private const val PREFS = "playlists_prefs"
    private const val KEY_NAMES = "playlist_names"
    private const val KEY_HIDDEN_FILES = "hidden_playlist_files"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun playlistsDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Music/Playlists")
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (e: Exception) { /* no storage access yet */ }
        return dir
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "playlist" }

    private fun m3uFile(name: String): File = File(playlistsDir(), "${sanitizeFileName(name)}.m3u8")

    private fun names(context: Context): MutableSet<String> =
        prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()

    private fun hiddenFiles(context: Context): MutableSet<String> =
        prefs(context).getStringSet(KEY_HIDDEN_FILES, emptySet())!!.toMutableSet()

    /**
     * Imports .m3u/.m3u8 files from Music/Playlists that are new to the app
     * or were edited by another app since this app last changed them. This
     * is what makes a fresh install pick playlists back up automatically.
     */
    fun syncFromDisk(context: Context) {
        try {
            val files = playlistsDir().listFiles { f ->
                f.isFile && (f.extension.equals("m3u", true) || f.extension.equals("m3u8", true))
            } ?: return

            val names = names(context)
            val hidden = hiddenFiles(context)
            val editor = prefs(context).edit()
            var hiddenChanged = false

            for (file in files) {
                val name = file.nameWithoutExtension
                if (name in hidden) {
                    // Left behind by a rename/delete we couldn't finish on disk.
                    if (FileOrganizer.hasFullStorageAccess() && file.delete()) {
                        hidden.remove(name)
                        hiddenChanged = true
                    }
                    continue
                }
                val known = names.any { it == name }
                val lastLocalEdit = prefs(context).getLong(editKey(name), 0L)
                if (known && file.lastModified() <= lastLocalEdit + 2_000) continue

                names.add(name)
                editor.putString(songsKey(name), readM3u(file).joinToString("|"))
            }
            editor.putStringSet(KEY_NAMES, names)
            if (hiddenChanged) editor.putStringSet(KEY_HIDDEN_FILES, hidden)
            editor.commit()
        } catch (e: Exception) {
            // No storage access yet, or nothing to import — the SharedPreferences
            // copy still works fine on its own.
        }
    }

    fun getPlaylistNames(context: Context): List<String> {
        syncFromDisk(context)
        return names(context).toList().sortedBy { it.lowercase() }
    }

    fun createPlaylist(context: Context, name: String) {
        val names = names(context)
        names.add(name)
        prefs(context).edit().putStringSet(KEY_NAMES, names).commit()
        unhide(context, name)
        saveSongs(context, name, emptyList())
    }

    fun deletePlaylist(context: Context, name: String) {
        val names = names(context)
        names.remove(name)
        prefs(context).edit()
            .putStringSet(KEY_NAMES, names)
            .remove(songsKey(name))
            .remove(editKey(name))
            .commit()
        removeFileOrHide(context, name)
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songPath: String) {
        val paths = getSongPaths(context, playlistName).toMutableList()
        if (!paths.contains(songPath)) paths.add(songPath)
        saveSongs(context, playlistName, paths)
    }

    fun removeSongFromPlaylist(context: Context, playlistName: String, songPath: String) {
        val paths = getSongPaths(context, playlistName).toMutableList()
        paths.remove(songPath)
        saveSongs(context, playlistName, paths)
    }

    /**
     * Saves a new song order (from drag-and-drop on the playlist screen).
     * [orderedPaths] is the order the user arranged; any songs the screen
     * couldn't show (files currently missing from the library) keep their
     * place at the end rather than being dropped.
     */
    fun reorderPlaylist(context: Context, playlistName: String, orderedPaths: List<String>) {
        val existing = getSongPaths(context, playlistName)
        val existingSet = existing.toSet()
        val ordered = orderedPaths.filter { it in existingSet }.distinct()
        val orderedSet = ordered.toSet()
        saveSongs(context, playlistName, ordered + existing.filter { it !in orderedSet })
    }

    sealed class RenameResult {
        object Ok : RenameResult()
        object Blank : RenameResult()
        object AlreadyExists : RenameResult()
    }

    /** Renames a playlist in place — same songs, same order, no copy left behind. */
    fun renamePlaylist(context: Context, oldName: String, newNameRaw: String): RenameResult {
        val newName = newNameRaw.trim()
        if (newName.isEmpty()) return RenameResult.Blank
        if (newName == oldName) return RenameResult.Ok
        val names = names(context)
        // Case-insensitive: shared storage treats "Gym" and "gym" as the same file.
        if (names.any { it != oldName && it.equals(newName, ignoreCase = true) }) {
            return RenameResult.AlreadyExists
        }

        val paths = getSongPaths(context, oldName)
        names.remove(oldName)
        names.add(newName)
        prefs(context).edit()
            .putStringSet(KEY_NAMES, names)
            .remove(songsKey(oldName))
            .remove(editKey(oldName))
            .commit()

        // Remove (or hide) the old file *before* writing the new one: on
        // shared storage a case-only rename ("gym" → "Gym") is the same file.
        removeFileOrHide(context, oldName)
        unhide(context, newName)
        saveSongs(context, newName, paths)
        return RenameResult.Ok
    }

    fun getSongPaths(context: Context, playlistName: String): List<String> {
        val raw = prefs(context).getString(songsKey(playlistName), "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("|")
    }

    fun getSongsInPlaylist(context: Context, playlistName: String, allSongs: List<Song>): List<Song> {
        // Preserve the playlist's own order (not allSongs' sort order).
        val paths = getSongPaths(context, playlistName)
        val byPath = allSongs.associateBy { it.path }
        return paths.mapNotNull { byPath[it] }
    }

    /** True once playlists are actually being written as portable files, not just cached locally. */
    fun isPortable(): Boolean = FileOrganizer.hasFullStorageAccess()

    /** Saves to the local copy and the .m3u8 file, and stamps the time of this edit. */
    private fun saveSongs(context: Context, name: String, paths: List<String>) {
        prefs(context).edit().putString(songsKey(name), paths.joinToString("|")).commit()
        writeM3uSafely(name, paths)
        // Stamp *after* writing so our own write never looks like an outside edit.
        prefs(context).edit().putLong(editKey(name), System.currentTimeMillis()).commit()
    }

    private fun removeFileOrHide(context: Context, name: String) {
        val file = m3uFile(name)
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) { /* no permission */ }
        val stillThere = try { file.exists() } catch (e: Exception) { false }
        if (stillThere) {
            val hidden = hiddenFiles(context)
            hidden.add(file.nameWithoutExtension)
            prefs(context).edit().putStringSet(KEY_HIDDEN_FILES, hidden).commit()
        }
    }

    private fun unhide(context: Context, name: String) {
        val hidden = hiddenFiles(context)
        val base = sanitizeFileName(name)
        if (hidden.removeAll { it.equals(base, ignoreCase = true) }) {
            prefs(context).edit().putStringSet(KEY_HIDDEN_FILES, hidden).commit()
        }
    }

    private fun writeM3uSafely(name: String, paths: List<String>) {
        try {
            val file = m3uFile(name)
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("#EXTM3U\n")
                for (path in paths) {
                    writer.write("#EXTINF:-1,${File(path).nameWithoutExtension}\n")
                    writer.write("$path\n")
                }
            }
        } catch (e: Exception) {
            // No permission to write this file — the local copy still works,
            // it just isn't portable/reinstall-proof until access is granted.
        }
    }

    private fun readM3u(file: File): List<String> {
        return try {
            file.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun songsKey(playlistName: String) = "playlist_songs_$playlistName"
    private fun editKey(playlistName: String) = "playlist_edited_$playlistName"
}
