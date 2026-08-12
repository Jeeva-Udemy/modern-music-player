package com.claude.musicplayer

import android.content.Context
import android.content.Intent

object PlaybackController {

    /**
     * Starts the given queue playing from [index]. If that exact song is
     * already the one loaded (playing or paused), this is a no-op — tapping
     * a song that's already current shouldn't restart it from 0.
     */
    fun play(context: Context, songs: List<Song>, index: Int) {
        val song = songs.getOrNull(index) ?: return
        if (song.path == MusicService.nowPlayingPath) return

        MusicService.currentQueue = songs
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_QUEUE
            putExtra(MusicService.EXTRA_INDEX, index)
        }
        context.startService(intent)
    }

    /**
     * Starts playback and opens the full-screen Now Playing UI. If the
     * tapped song is already the one playing, this just opens the player
     * without restarting it.
     */
    fun playAndOpenNowPlaying(context: Context, songs: List<Song>, index: Int) {
        play(context, songs, index)
        context.startActivity(Intent(context, NowPlayingActivity::class.java))
    }
}
