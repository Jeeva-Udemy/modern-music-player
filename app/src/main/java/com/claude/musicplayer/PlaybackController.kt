package com.claude.musicplayer

import android.content.Context
import android.content.Intent

object PlaybackController {

    /**
     * Starts the given queue playing from [index]. If that exact song is
     * already the one loaded (playing or paused), this is a no-op — tapping
     * a song that's already current shouldn't restart it from 0.
     *
     * If nothing is currently loaded but this exact song is the one that
     * was last playing (e.g. right after reopening the app), it resumes
     * from the saved position instead of starting over from 0 — unless an
     * explicit [resumePositionMs] was already given.
     */
    fun play(context: Context, songs: List<Song>, index: Int, resumePositionMs: Int = 0) {
        val song = songs.getOrNull(index) ?: return
        if (song.path == MusicService.nowPlayingPath) return

        val effectiveResumeMs = if (resumePositionMs == 0 && MusicService.nowPlayingPath == null) {
            val stored = PlaybackStateStore.load(context)
            if (stored != null && stored.first.path == song.path) stored.second else 0
        } else {
            resumePositionMs
        }

        MusicService.currentQueue = songs
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_QUEUE
            putExtra(MusicService.EXTRA_INDEX, index)
            putExtra(MusicService.EXTRA_RESUME_POSITION_MS, effectiveResumeMs)
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

    /**
     * Resumes whatever was last playing before the service was stopped
     * (e.g. the app was closed while paused), picking up from where it
     * left off. Returns false if there's nothing to resume.
     */
    fun resumeLastPlayed(context: Context): Boolean {
        val (song, position) = PlaybackStateStore.load(context) ?: return false
        play(context, listOf(song), 0, resumePositionMs = position)
        return true
    }

    /**
     * Tells the service a file was deleted, so it can stop/skip immediately
     * if that file was the one currently playing — otherwise the file
     * descriptor stays open and playback silently continues from the
     * now-deleted file.
     */
    fun notifySongRemoved(context: Context, path: String) {
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_SONG_REMOVED
            putExtra(MusicService.EXTRA_REMOVED_PATH, path)
        }
        context.startService(intent)
    }
}
