package com.claude.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * Wires up a mini player bar (art, title/artist, seek bar, prev/play-pause/
 * next, tap-anywhere-else-to-open-the-full-player) against the currently
 * running MusicService. Used by MainActivity and by the Folder/Playlist
 * contents screens so playback controls stay reachable no matter where you
 * are in the app, not just on the main tabs.
 *
 * When nothing is currently loaded (e.g. right after reopening the app,
 * since the service now stops itself once you close the app while idle),
 * this falls back to showing whatever was last played and lets you tap to
 * resume it from where you left off.
 */
class MiniPlayerController(
    private val context: Context,
    private val miniPlayerBar: View,
    private val titleView: TextView,
    private val artistView: TextView,
    private val artView: ImageView,
    private val playPauseButton: ImageButton,
    private val prevButton: ImageButton,
    private val nextButton: ImageButton,
    private val seekBar: SeekBar
) {
    private val clickTarget: View = miniPlayerBar.findViewById(R.id.miniPlayerCard) ?: miniPlayerBar
    private val volumeButton: ImageButton? = miniPlayerBar.findViewById(R.id.miniVolumeButton)
    private val playPauseCard: MaterialCardView? = miniPlayerBar.findViewById(R.id.miniPlayPauseCard)
    private var accent: Int = ArtworkTheme.accentFor(context)

    // Play button and seek bar follow the colours of the current artwork.
    private val artworkListener = ArtworkTheme.Listener {
        accent = ArtworkTheme.accentFor(context)
        playPauseCard?.setCardBackgroundColor(accent)
        playPauseButton.imageTintList = ColorStateList.valueOf(ArtworkTheme.onColorFor(accent))
        ArtworkTheme.tintSeekBar(seekBar, accent)
    }

    private var musicService: MusicService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastArtSongId: Long = -1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.LocalBinder).getService()
            bound = true
            startProgressUpdates()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    fun start() {
        ArtworkTheme.addListener(context, artworkListener)
        volumeButton?.setOnClickListener { VolumeController.showPopup(it, accent) }
        clickTarget.setOnClickListener {
            if (musicService?.getCurrentSong() != null) {
                context.startActivity(Intent(context, NowPlayingActivity::class.java))
            } else if (PlaybackController.resumeLastPlayed(context)) {
                context.startActivity(Intent(context, NowPlayingActivity::class.java))
            }
        }
        playPauseButton.setOnClickListener {
            val svc = musicService
            if (svc?.getCurrentSong() != null) {
                if (svc.isPlaying()) svc.pause() else svc.resume()
                updatePlayPauseIcon()
            } else {
                PlaybackController.resumeLastPlayed(context)
            }
        }
        nextButton.setOnClickListener { musicService?.playNext() }
        prevButton.setOnClickListener { musicService?.playPrevious() }

        Intent(context, MusicService::class.java).also {
            context.bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stop() {
        ArtworkTheme.removeListener(artworkListener)
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun updatePlayPauseIcon() {
        val playing = musicService?.isPlaying() == true
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun startProgressUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                val svc = musicService
                val song = svc?.getCurrentSong()
                if (svc != null && song != null) {
                    titleView.text = song.title
                    artistView.text = song.artist
                    if (song.id != lastArtSongId) {
                        lastArtSongId = song.id
                        showArt(song)
                    }
                    val duration = svc.getDurationMs()
                    if (duration > 0) {
                        seekBar.max = duration
                        seekBar.progress = svc.getCurrentPositionMs()
                    }
                    updatePlayPauseIcon()
                } else {
                    showLastPlayedFallback()
                }
                handler.postDelayed(this, 500)
            }
        })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (musicService?.getCurrentSong() != null) {
                    musicService?.seekTo(sb?.progress ?: 0)
                }
            }
        })
    }

    private fun showArt(song: Song) {
        val bitmap = MusicRepository.loadAlbumArt(context, song.albumArtUri, 160)
        if (bitmap != null) {
            artView.imageTintList = null
            artView.setPadding(0, 0, 0, 0)
            artView.setImageBitmap(bitmap)
        } else {
            val pad = (10 * context.resources.displayMetrics.density).toInt()
            artView.imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            artView.setPadding(pad, pad, pad, pad)
            artView.setImageResource(R.drawable.ic_music_note)
        }
    }

    /** Nothing is loaded right now — show whatever was last played instead of a blank bar. */
    private fun showLastPlayedFallback() {
        val loaded = PlaybackStateStore.load(context)
        if (loaded != null) {
            val (song, position) = loaded
            titleView.text = song.title
            artistView.text = song.artist
            if (song.id != lastArtSongId) {
                lastArtSongId = song.id
                showArt(song)
            }
            if (song.duration > 0) {
                seekBar.max = song.duration.toInt()
                seekBar.progress = position
            }
        } else {
            titleView.text = "Nothing playing"
            artistView.text = ""
        }
        playPauseButton.setImageResource(R.drawable.ic_play)
    }
}
