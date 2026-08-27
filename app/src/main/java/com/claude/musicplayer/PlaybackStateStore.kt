package com.claude.musicplayer

import android.content.Context

/**
 * Remembers the last song that was loaded (and how far into it you were),
 * so the mini player can still show "what was playing" and let you resume
 * it even after the service has stopped — which now happens automatically
 * once the app is closed while nothing is actively playing (see
 * MusicService.onTaskRemoved), instead of lingering in the background
 * indefinitely.
 */
object PlaybackStateStore {
    private const val PREFS = "playback_state_prefs"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_SONG_TITLE = "song_title"
    private const val KEY_SONG_ARTIST = "song_artist"
    private const val KEY_SONG_ALBUM = "song_album"
    private const val KEY_SONG_DURATION = "song_duration"
    private const val KEY_SONG_PATH = "song_path"
    private const val KEY_SONG_FOLDER = "song_folder"
    private const val KEY_SONG_ART_URI = "song_art_uri"
    private const val KEY_SONG_DATE_ADDED = "song_date_added"
    private const val KEY_POSITION_MS = "position_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, song: Song, positionMs: Int) {
        prefs(context).edit()
            .putLong(KEY_SONG_ID, song.id)
            .putString(KEY_SONG_TITLE, song.title)
            .putString(KEY_SONG_ARTIST, song.artist)
            .putString(KEY_SONG_ALBUM, song.album)
            .putLong(KEY_SONG_DURATION, song.duration)
            .putString(KEY_SONG_PATH, song.path)
            .putString(KEY_SONG_FOLDER, song.folder)
            .putString(KEY_SONG_ART_URI, song.albumArtUri)
            .putLong(KEY_SONG_DATE_ADDED, song.dateAdded)
            .putInt(KEY_POSITION_MS, positionMs)
            .apply()
    }

    /** Returns the last-known song and playback position, or null if there isn't one. */
    fun load(context: Context): Pair<Song, Int>? {
        val p = prefs(context)
        val path = p.getString(KEY_SONG_PATH, null) ?: return null
        val song = Song(
            id = p.getLong(KEY_SONG_ID, -1),
            title = p.getString(KEY_SONG_TITLE, "") ?: "",
            artist = p.getString(KEY_SONG_ARTIST, "") ?: "",
            album = p.getString(KEY_SONG_ALBUM, "") ?: "",
            duration = p.getLong(KEY_SONG_DURATION, 0),
            path = path,
            folder = p.getString(KEY_SONG_FOLDER, "") ?: "",
            albumArtUri = p.getString(KEY_SONG_ART_URI, null),
            dateAdded = p.getLong(KEY_SONG_DATE_ADDED, 0)
        )
        val position = p.getInt(KEY_POSITION_MS, 0)
        return song to position
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
