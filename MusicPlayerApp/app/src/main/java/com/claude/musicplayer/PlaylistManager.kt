package com.claude.musicplayer

import android.content.Context

/**
 * Minimal playlist storage: playlist name -> ordered list of song file paths,
 * persisted as simple delimited strings in SharedPreferences. Good enough
 * for a lightweight player; swap for a Room database if you need more.
 */
object PlaylistManager {
    private const val PREFS = "playlists_prefs"
    private const val KEY_NAMES = "playlist_names"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPlaylistNames(context: Context): List<String> {
        return prefs(context).getStringSet(KEY_NAMES, emptySet())?.toList()?.sorted() ?: emptyList()
    }

    fun createPlaylist(context: Context, name: String) {
        val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
        names.add(name)
        prefs(context).edit().putStringSet(KEY_NAMES, names).apply()
    }

    fun deletePlaylist(context: Context, name: String) {
        val names = prefs(context).getStringSet(KEY_NAMES, emptySet())!!.toMutableSet()
        names.remove(name)
        prefs(context).edit()
            .putStringSet(KEY_NAMES, names)
            .remove(songsKey(name))
            .apply()
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songPath: String) {
        val paths = getSongPaths(context, playlistName).toMutableList()
        if (!paths.contains(songPath)) paths.add(songPath)
        prefs(context).edit().putString(songsKey(playlistName), paths.joinToString("|")).apply()
    }

    fun getSongPaths(context: Context, playlistName: String): List<String> {
        val raw = prefs(context).getString(songsKey(playlistName), "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("|")
    }

    fun getSongsInPlaylist(context: Context, playlistName: String, allSongs: List<Song>): List<Song> {
        val paths = getSongPaths(context, playlistName).toSet()
        return allSongs.filter { it.path in paths }
    }

    private fun songsKey(playlistName: String) = "playlist_songs_$playlistName"
}
