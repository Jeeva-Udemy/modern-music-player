package com.claude.musicplayer

import android.content.Context

/**
 * Remembers the chosen sort order — separately for the Songs tab and for
 * playlist contents — until the user picks a different one.
 *
 * Stored as stable string keys rather than raw R.id ints: resource ids are
 * regenerated on every build, so a saved int could silently stop matching
 * after an app update and the sort would appear to "reset".
 */
object SortPreference {
    private const val PREFS = "sort_prefs"
    private const val KEY_SONGS = "songs_sort_key"
    private const val KEY_PLAYLIST = "playlist_sort_key"

    private val idToKey: Map<Int, String> by lazy {
        mapOf(
            R.id.sort_playlist_order to "playlist_order",
            R.id.sort_title_asc to "title_asc",
            R.id.sort_title_desc to "title_desc",
            R.id.sort_artist to "artist",
            R.id.sort_duration to "duration",
            R.id.sort_newest to "newest",
            R.id.sort_oldest to "oldest"
        )
    }

    private val idToLabel: Map<Int, String> by lazy {
        mapOf(
            R.id.sort_playlist_order to "Playlist order",
            R.id.sort_title_asc to "Title A–Z",
            R.id.sort_title_desc to "Title Z–A",
            R.id.sort_artist to "Artist",
            R.id.sort_duration to "Duration",
            R.id.sort_newest to "Newest",
            R.id.sort_oldest to "Oldest"
        )
    }

    private fun prefKey(forPlaylist: Boolean) = if (forPlaylist) KEY_PLAYLIST else KEY_SONGS

    fun save(context: Context, menuItemId: Int, forPlaylist: Boolean = false) {
        val key = idToKey[menuItemId] ?: return
        // commit() (not apply()) so the choice is on disk even if the app is
        // swiped away a moment after picking it.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(prefKey(forPlaylist), key).commit()
    }

    /**
     * Songs tab defaults to Title (A–Z); playlists default to the order songs
     * were added in — until the user has picked something else.
     */
    fun load(context: Context, forPlaylist: Boolean = false): Int {
        val default = if (forPlaylist) R.id.sort_playlist_order else R.id.sort_title_asc
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(prefKey(forPlaylist), null) ?: return default
        val id = idToKey.entries.firstOrNull { it.value == saved }?.key ?: return default
        // "Playlist order" only exists inside playlists.
        if (!forPlaylist && id == R.id.sort_playlist_order) return default
        return id
    }

    fun label(menuItemId: Int): String = idToLabel[menuItemId] ?: "Sort"
}
