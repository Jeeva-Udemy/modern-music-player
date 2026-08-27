package com.claude.musicplayer

import android.app.*
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Foreground service that owns playback + the MediaSession. A MediaStyle
 * notification built from an active MediaSession is what Android renders
 * on the lock screen (play/pause/skip, seek bar, art) — this is exactly
 * the UI shown in the reference screenshot.
 */
class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.claude.musicplayer.PLAY"
        const val ACTION_PAUSE = "com.claude.musicplayer.PAUSE"
        const val ACTION_NEXT = "com.claude.musicplayer.NEXT"
        const val ACTION_PREV = "com.claude.musicplayer.PREV"
        const val ACTION_PLAY_QUEUE = "com.claude.musicplayer.PLAY_QUEUE"
        const val ACTION_SONG_REMOVED = "com.claude.musicplayer.SONG_REMOVED"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_REMOVED_PATH = "extra_removed_path"
        const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"

        // The queue lives here (in-process) rather than being passed through
        // Intent extras. Large libraries (1000+ songs) blow past Android's
        // ~1MB Binder transaction limit when serialized into an Intent,
        // which crashes the app with TransactionTooLargeException — this
        // avoids that entirely since no serialization happens.
        var currentQueue: List<Song> = emptyList()

        // Cheap, poll-friendly playback state for list screens to highlight
        // the currently playing row without needing to bind the service.
        var nowPlayingPath: String? = null
        var nowPlayingIsActive: Boolean = false
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var currentIndex = 0

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    // True only when WE paused playback because of a focus loss (another
    // app's audio, a phone call, etc.) — not when the user tapped pause
    // themselves. Only in that case do we auto-resume on focus gain.
    private var pausedDueToFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Long-term loss (another app is now the main audio owner) —
                // pause and don't assume we should resume automatically.
                pausedDueToFocusLoss = false
                if (isPlaying()) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Short-term loss — a call ringing, a notification sound, a
                // video briefly grabbing audio. Pause and remember to resume.
                if (isPlaying()) {
                    pausedDueToFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedDueToFocusLoss) {
                    pausedDueToFocusLoss = false
                    resume()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    var shuffleEnabled = false
        private set
    var repeatEnabled = false
        private set

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
    }

    fun toggleRepeat() {
        repeatEnabled = !repeatEnabled
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_PLAY_QUEUE -> {
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                val resumePositionMs = intent.getIntExtra(EXTRA_RESUME_POSITION_MS, 0)
                playSongAt(index, resumePositionMs = resumePositionMs)
            }
            ACTION_SONG_REMOVED -> {
                handleSongRemoved(intent.getStringExtra(EXTRA_REMOVED_PATH))
            }
        }
        return START_STICKY
    }

    fun playSongAt(index: Int, resumePositionMs: Int = 0) {
        playSongAt(index, attemptsRemaining = currentQueue.size, resumePositionMs = resumePositionMs)
    }

    /**
     * [attemptsRemaining] caps how many times we'll auto-skip a failing
     * track before giving up — without it, a bad file (or a whole queue of
     * bad files) could otherwise recurse indefinitely. [resumePositionMs]
     * seeks to that point once playback starts — used when restoring the
     * last-played song after the service was stopped/killed.
     */
    private fun playSongAt(index: Int, attemptsRemaining: Int, resumePositionMs: Int = 0) {
        if (currentQueue.isEmpty()) return
        if (attemptsRemaining <= 0) {
            Toast.makeText(this, "None of the songs in this queue could be played.", Toast.LENGTH_LONG).show()
            return
        }
        if (index !in currentQueue.indices) return
        currentIndex = index
        val song = currentQueue[index]

        mediaPlayer?.release()
        mediaPlayer = null

        try {
            if (!requestAudioFocus()) {
                Toast.makeText(this, "Couldn't get audio focus — something else is using audio.", Toast.LENGTH_SHORT).show()
                return
            }
            pausedDueToFocusLoss = false
            // Use the content:// URI (not the raw file path) — scoped storage
            // on Android 10+ blocks direct file-path access from other apps'
            // storage areas, which silently breaks setDataSource(path).
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(applicationContext, uri)
                setOnCompletionListener { playNext() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@MusicService, "Skipping \"${song.title}\" — couldn't play it", Toast.LENGTH_SHORT).show()
                    skipForwardOnFailure(index, attemptsRemaining)
                    true
                }
                prepare()
                start()
                if (resumePositionMs > 0) seekTo(resumePositionMs)
            }
            updateMetadata(song)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying = true))
            nowPlayingPath = song.path
            nowPlayingIsActive = true
            PlaybackStateStore.save(this, song, resumePositionMs)
        } catch (e: Exception) {
            Toast.makeText(this, "Skipping \"${song.title}\" — couldn't play it", Toast.LENGTH_SHORT).show()
            skipForwardOnFailure(index, attemptsRemaining)
        }
    }

    private fun skipForwardOnFailure(failedIndex: Int, attemptsRemaining: Int) {
        val next = computeNextIndex(failedIndex) ?: return
        playSongAt(next, attemptsRemaining - 1)
    }

    /**
     * Called when a song file gets deleted elsewhere in the app. Deleting a
     * file that's actively open for playback doesn't stop it by itself —
     * Unix file handles stay valid until explicitly closed, so without this
     * the MediaPlayer just keeps reading the already-open (now unlinked)
     * file and plays on as if nothing happened. This releases that stale
     * handle and moves on immediately if the removed song was the one
     * currently loaded.
     */
    private fun handleSongRemoved(removedPath: String?) {
        if (removedPath == null) return
        val removedIndex = currentQueue.indexOfFirst { it.path == removedPath }
        if (removedIndex == -1) return

        val wasCurrent = removedIndex == currentIndex

        val newQueue = currentQueue.toMutableList()
        newQueue.removeAt(removedIndex)
        if (removedIndex < currentIndex) {
            currentIndex -= 1
        }
        currentQueue = newQueue

        if (!wasCurrent) return

        mediaPlayer?.release()
        mediaPlayer = null
        nowPlayingPath = null
        nowPlayingIsActive = false

        if (currentQueue.isEmpty()) {
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(true)
            stopSelf()
        } else {
            val nextIndex = if (currentIndex in currentQueue.indices) currentIndex else 0
            playSongAt(nextIndex)
        }
    }

    /** The song currently loaded (playing or paused), if any. */
    fun getCurrentSong(): Song? = currentQueue.getOrNull(currentIndex)

    fun resume() {
        mediaPlayer?.start()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        nowPlayingIsActive = true
        currentQueue.getOrNull(currentIndex)?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, isPlaying = true))
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        nowPlayingIsActive = false
        currentQueue.getOrNull(currentIndex)?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, isPlaying = false))
            PlaybackStateStore.save(this, it, getCurrentPositionMs())
        }
    }

    fun playNext() {
        val next = computeNextIndex(currentIndex) ?: return
        playSongAt(next)
    }

    /**
     * Works out which index to advance to from [from], honoring shuffle/
     * repeat. Returns null when there's nowhere to go (end of a
     * non-repeating queue) — the caller should just stop in that case.
     */
    private fun computeNextIndex(from: Int): Int? {
        if (currentQueue.isEmpty()) return null
        return if (shuffleEnabled && currentQueue.size > 1) {
            var candidate: Int
            do {
                candidate = currentQueue.indices.random()
            } while (candidate == from)
            candidate
        } else {
            val n = from + 1
            when {
                n < currentQueue.size -> n
                repeatEnabled -> 0
                else -> null
            }
        }
    }

    fun playPrevious() {
        if (currentQueue.isEmpty()) return
        playSongAt((currentIndex - 1 + currentQueue.size) % currentQueue.size)
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
        updatePlaybackState(
            if (mediaPlayer?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING
            else PlaybackStateCompat.STATE_PAUSED
        )
    }

    fun getCurrentPositionMs(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDurationMs(): Int = mediaPlayer?.duration ?: 0
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun updateMetadata(song: Song) {
        val art = try {
            song.albumArtUri?.let {
                contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream)
            }
        } catch (e: Exception) { null }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            .apply { if (art != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) }
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, getCurrentPositionMs().toLong(), 1f)
                .build()
        )
    }

    /**
     * Builds the MediaStyle notification. This is what the system renders
     * on the lock screen: album art, title/artist, seek bar and transport
     * controls — matching the reference screenshot.
     */
    private fun buildNotification(song: Song, isPlaying: Boolean): Notification {
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                servicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                servicePendingIntent(ACTION_PLAY)
            )
        }

        val art = try {
            song.albumArtUri?.let {
                contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream)
            }
        } catch (e: Exception) { null }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(art)
            .setContentIntent(openAppPendingIntent())
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                servicePendingIntent(ACTION_PREV)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                servicePendingIntent(ACTION_NEXT)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColorized(true)
            .setColor(0xFF7B2FF7.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, NowPlayingActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows the currently playing song"
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Called when the app's task is removed from Recents (user swipes it
     * away or force-closes it). Without this, the service just keeps
     * running indefinitely even with nothing playing — which is exactly
     * what shows up as a phantom "Music Player running for 55 hr" entry in
     * the system's battery/background-services list. If something is
     * actively playing we let it continue (same expectation as any other
     * music app); otherwise there's no reason to keep the process alive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        getCurrentSong()?.let { PlaybackStateStore.save(this, it, getCurrentPositionMs()) }
        if (!isPlaying()) {
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        getCurrentSong()?.let { PlaybackStateStore.save(this, it, getCurrentPositionMs()) }
        nowPlayingPath = null
        nowPlayingIsActive = false
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaSession.release()
        super.onDestroy()
    }
}
