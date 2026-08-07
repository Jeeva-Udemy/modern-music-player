package com.claude.musicplayer

import android.content.Context
import android.content.Intent

object PlaybackController {

    /**
     * Starts the given queue playing from [index]. The queue is handed to
     * the service directly in-process (not via Intent extras) — Android's
     * Binder transaction has a ~1MB limit, and serializing a large song
     * list (hundreds/thousands of tracks) through an Intent crashes the
     * app with TransactionTooLargeException. Setting the static field is
     * safe here since the service always runs in the same process.
     */
    fun play(context: Context, songs: List<Song>, index: Int) {
        MusicService.currentQueue = songs
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_QUEUE
            putExtra(MusicService.EXTRA_INDEX, index)
        }
        context.startService(intent)
    }

    /** Starts playback and opens the full-screen Now Playing UI, like a normal music app. */
    fun playAndOpenNowPlaying(context: Context, songs: List<Song>, index: Int) {
        play(context, songs, index)
        context.startActivity(Intent(context, NowPlayingActivity::class.java))
    }
}
