package com.claude.musicplayer

import android.app.*
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
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
        const val EXTRA_INDEX = "extra_index"

        // The queue lives here (in-process) rather than being passed through
        // Intent extras. Large libraries (1000+ songs) blow past Android's
        // ~1MB Binder transaction limit when serialized into an Intent,
        // which crashes the app with TransactionTooLargeException — this
        // avoids that entirely since no serialization happens.
        var currentQueue: List<Song> = emptyList()
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var currentIndex = 0
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
                playSongAt(index)
            }
        }
        return START_STICKY
    }

    fun playSongAt(index: Int) {
        if (index !in currentQueue.indices) return
        currentIndex = index
        val song = currentQueue[index]

        mediaPlayer?.release()
        mediaPlayer = null

        try {
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
                    Toast.makeText(this@MusicService, "Couldn't play \"${song.title}\"", Toast.LENGTH_SHORT).show()
                    true
                }
                prepare()
                start()
            }
            updateMetadata(song)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying = true))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play \"${song.title}\": ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** The song currently loaded (playing or paused), if any. */
    fun getCurrentSong(): Song? = currentQueue.getOrNull(currentIndex)

    fun resume() {
        mediaPlayer?.start()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        currentQueue.getOrNull(currentIndex)?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, isPlaying = true))
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        currentQueue.getOrNull(currentIndex)?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, isPlaying = false))
        }
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return
        val nextIndex: Int = if (shuffleEnabled && currentQueue.size > 1) {
            var candidate: Int
            do {
                candidate = currentQueue.indices.random()
            } while (candidate == currentIndex)
            candidate
        } else {
            val n = currentIndex + 1
            when {
                n < currentQueue.size -> n
                repeatEnabled -> 0
                else -> return // reached the end, not repeating — just stop
            }
        }
        playSongAt(nextIndex)
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

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaSession.release()
        super.onDestroy()
    }
}
