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
 *    dropped into that same folder. That's as close to genuine
 *    cross-app compatibility as is achievable without every app agreeing
 *    on a shared database, which isn't something a single app can impose.
 *
 * A SharedPreferences copy is kept alongside as a fast local cache and as a
 * graceful fallback for devices that haven't granted "All files access" yet
 * (file writes are best-effort and silently skipped without it — the app
 * still works, it just won't be portable/reinstall-proof until granted).
 */
object PlaylistManager {
    private const val PREFS = "playlists_prefs"
    private const val KEY_NAMES = "playlist_names"

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

    /**
     * Looks for .m3u/.m3u8 files in Music/Playlists that aren't in the local
     * cache yet (or whose on-disk contents changed) and imports them. This
     * is what makes a fresh install pick playlists back up automatically,
     * and what lets this app see playlists dropped there by other apps.
     */
    fun syncFromDisk(context: Context) {
        try {
            val dir = playlistsDir()
            val files = dir.listFiles { f ->
                f.isFile && (f.extension.equals("m3u", true) || f.extension.equals("m3u8", true))
            } ?: return

            val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
            for (file in files) {
                val name = file.nameWithoutExtension
                names.add(name)
                val paths = readM3u(file)
                prefs(context).edit().putString(songsKey(name), paths.joinToString("|")).apply()
            }
            prefs(context).edit().putStringSet(KEY_NAMES, names).apply()
        } catch (e: Exception) {
            // No storage access yet, or nothing to import — the SharedPreferences
            // cache (if any) still works fine on its own.
        }
    }

    fun getPlaylistNames(context: Context): List<String> {
        syncFromDisk(context)
        return prefs(context).getStringSet(KEY_NAMES, emptySet())?.toList()?.sorted() ?: emptyList()
    }

    fun createPlaylist(context: Context, name: String) {
        val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
        names.add(name)
        prefs(context).edit().putStringSet(KEY_NAMES, names).apply()
        writeM3uSafely(name, emptyList())
    }

    fun deletePlaylist(context: Context, name: String) {
        val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
        names.remove(name)
        prefs(context).edit()
            .putStringSet(KEY_NAMES, names)
            .remove(songsKey(name))
            .apply()
        try {
            m3uFile(name).delete()
        } catch (e: Exception) { /* ignore */ }
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songPath: String) {
        val paths = getSongPaths(context, playlistName).toMutableList()
        if (!paths.contains(songPath)) paths.add(songPath)
        prefs(context).edit().putString(songsKey(playlistName), paths.joinToString("|")).apply()
        writeM3uSafely(playlistName, paths)
    }

    fun removeSongFromPlaylist(context: Context, playlistName: String, songPath: String) {
        val paths = getSongPaths(context, playlistName).toMutableList()
        paths.remove(songPath)
        prefs(context).edit().putString(songsKey(playlistName), paths.joinToString("|")).apply()
        writeM3uSafely(playlistName, paths)
    }

    /**
     * Saves a new song order (from drag-and-drop on the playlist screen).
     * [orderedPaths] is the order the user arranged; any songs the screen
     * couldn't show (files currently missing from the library) keep their
     * place at the end rather than being dropped. Written to both the local
     * cache and the playlist's .m3u8 file, so the order survives closing,
     * reinstalling or uninstalling the app (the file part needs "All files
     * access").
     */
    fun reorderPlaylist(context: Context, playlistName: String, orderedPaths: List<String>) {
        val existing = getSongPaths(context, playlistName)
        val existingSet = existing.toSet()
        val ordered = orderedPaths.filter { it in existingSet }.distinct()
        val orderedSet = ordered.toSet()
        val result = ordered + existing.filter { it !in orderedSet }
        prefs(context).edit().putString(songsKey(playlistName), result.joinToString("|")).commit()
        writeM3uSafely(playlistName, result)
    }

    sealed class RenameResult {
        object Ok : RenameResult()
        object Blank : RenameResult()
        object AlreadyExists : RenameResult()
    }

    /**
     * Renames a playlist — its entry in the local cache and its .m3u8 file
     * on disk — keeping every song and its order.
     */
    fun renamePlaylist(context: Context, oldName: String, newNameRaw: String): RenameResult {
        val newName = newNameRaw.trim()
        if (newName.isEmpty()) return RenameResult.Blank
        if (newName == oldName) return RenameResult.Ok
        val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
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
            .putString(songsKey(newName), paths.joinToString("|"))
            .commit()

        // Delete the old file before writing the new one: on shared storage
        // a case-only rename ("gym" → "Gym") is the same file, and deleting
        // afterwards would remove the freshly written playlist.
        try {
            m3uFile(oldName).delete()
        } catch (e: Exception) { /* no storage access */ }
        writeM3uSafely(newName, paths)
        return RenameResult.Ok
    }

    fun getSongPaths(context: Context, playlistName: String): List<String> {
        val raw = prefs(context).getString(songsKey(playlistName), "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("|")
    }

    fun getSongsInPlaylist(context: Context, playlistName: String, allSongs: List<Song>): List<Song> {
        // Preserve the order songs were added in (not allSongs' own sort
        // order) — map each stored path back to its Song, in that order.
        val paths = getSongPaths(context, playlistName)
        val byPath = allSongs.associateBy { it.path }
        return paths.mapNotNull { byPath[it] }
    }

    /** True once playlists are actually being written as portable files, not just cached locally. */
    fun isPortable(): Boolean = FileOrganizer.hasFullStorageAccess()

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
            // No "All files access" yet — the playlist still works via the
            // SharedPreferences cache, it just isn't portable/reinstall-proof
            // until permission is granted (same flow as Move/Delete features).
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
}
