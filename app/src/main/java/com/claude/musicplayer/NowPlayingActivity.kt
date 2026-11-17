package com.claude.musicplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit

class NowPlayingActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastBoundSongId: Long = -1
    private var userSeeking = false

    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var artView: ImageView
    private lateinit var artCard: MaterialCardView
    private lateinit var glowView: View
    private lateinit var pulseView: View
    private lateinit var playPauseCard: MaterialCardView
    private lateinit var playPauseButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var volume: VolumeController

    private var spinAnimator: ObjectAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var accent: Int = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.LocalBinder).getService()
            bound = true
            bindUiToCurrentSong()
            startProgressUpdates()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        AmbientBackgroundView.attach(this, strong = true)
        // Hardware volume keys control music volume on this screen even when paused.
        volumeControlStream = AudioManager.STREAM_MUSIC

        titleView = findViewById(R.id.npTitle)
        artistView = findViewById(R.id.npArtist)
        artView = findViewById(R.id.npArt)
        artCard = findViewById(R.id.npArtCard)
        glowView = findViewById(R.id.npGlow)
        pulseView = findViewById(R.id.npPulse)
        playPauseCard = findViewById(R.id.npPlayPauseCard)
        playPauseButton = findViewById(R.id.npPlayPause)
        shuffleButton = findViewById(R.id.npShuffle)
        repeatButton = findViewById(R.id.npRepeat)
        seekBar = findViewById(R.id.npSeekBar)
        volumeSeekBar = findViewById(R.id.npVolumeSeekBar)
        elapsedView = findViewById(R.id.npElapsed)
        durationView = findViewById(R.id.npDuration)
        titleView.isSelected = true // enables the marquee for long titles

        volume = VolumeController(this, volumeSeekBar, findViewById(R.id.npVolumeIcon))

        findViewById<ImageButton>(R.id.npBack).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.npNext).setOnClickListener {
            musicService?.playNext()
            handler.postDelayed({ bindUiToCurrentSong() }, 300)
        }
        findViewById<ImageButton>(R.id.npPrev).setOnClickListener {
            musicService?.playPrevious()
            handler.postDelayed({ bindUiToCurrentSong() }, 300)
        }
        playPauseButton.setOnClickListener {
            val svc = musicService
            if (svc?.getCurrentSong() != null) {
                if (svc.isPlaying()) svc.pause() else svc.resume()
                updatePlayPauseIcon()
            } else if (PlaybackController.resumeLastPlayed(this)) {
                handler.postDelayed({ bindUiToCurrentSong() }, 300)
            }
        }
        shuffleButton.setOnClickListener {
            musicService?.toggleShuffle()
            updateToggleButtonStates()
        }
        repeatButton.setOnClickListener {
            musicService?.toggleRepeat()
            updateToggleButtonStates()
        }
        findViewById<ImageButton>(R.id.npAddToPlaylist).setOnClickListener {
            musicService?.getCurrentSong()?.let { song ->
                PlaylistDialogHelper.showAddToPlaylistDialog(this, song)
            }
        }

        setupAnimations()

        // Buttons, sliders and the glow take their colour from the artwork.
        accent = ArtworkTheme.accentFor(this)
        ArtworkTheme.bind(this, this) { applyAccent() }

        Intent(this, MusicService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupAnimations() {
        // Vinyl-style rotation of the cover while playing.
        spinAnimator = ObjectAnimator.ofFloat(artCard, View.ROTATION, 0f, 360f).apply {
            duration = 24_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        // A ring that breathes out from the play button.
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                val s = 0.8f + 0.25f * f
                pulseView.scaleX = s
                pulseView.scaleY = s
                pulseView.alpha = 0.7f * (1f - f)
            }
        }
        pulseView.alpha = 0f
    }

    private fun applyAccent() {
        accent = ArtworkTheme.accentFor(this)
        val onAccent = ArtworkTheme.onColorFor(accent)
        playPauseCard.setCardBackgroundColor(accent)
        playPauseButton.imageTintList = ColorStateList.valueOf(onAccent)
        pulseView.background.mutate().setTint(accent)
        glowView.background.mutate().setTint(accent)
        artCard.strokeColor = ColorUtils.setAlphaComponent(accent, 160)
        ArtworkTheme.tintSeekBar(seekBar, accent)
        ArtworkTheme.tintSeekBar(volumeSeekBar, accent)
        updateToggleButtonStates()
    }

    private fun bindUiToCurrentSong() {
        val song = musicService?.getCurrentSong() ?: return
        titleView.text = song.title
        artistView.text = song.artist
        if (song.id != lastBoundSongId) {
            lastBoundSongId = song.id
            artView.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
            artView.setPadding(dp(60), dp(60), dp(60), dp(60))
            artView.setImageResource(R.drawable.ic_music_note)
            val bitmap = MusicRepository.loadAlbumArt(this, song.albumArtUri)
            if (bitmap != null) {
                artView.imageTintList = null
                artView.setPadding(0, 0, 0, 0)
                artView.setImageBitmap(bitmap)
            }
            // A little 3D flip-in whenever the song changes.
            artCard.cameraDistance = 12000f * resources.displayMetrics.density
            artCard.rotationY = -70f
            artCard.alpha = 0.3f
            artCard.animate().rotationY(0f).alpha(1f).setDuration(550).start()
        }
        updatePlayPauseIcon()
        updateToggleButtonStates()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateToggleButtonStates() {
        val service = musicService ?: return
        val off = getColor(R.color.textSecondary)
        shuffleButton.imageTintList = ColorStateList.valueOf(if (service.shuffleEnabled) accent else off)
        repeatButton.imageTintList = ColorStateList.valueOf(if (service.repeatEnabled) accent else off)
    }

    private fun updatePlayPauseIcon() {
        val playing = musicService?.isPlaying() == true
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        setAnimationsRunning(playing)
    }

    private fun setAnimationsRunning(playing: Boolean) {
        val spin = spinAnimator ?: return
        val pulse = pulseAnimator ?: return
        if (playing) {
            if (!spin.isStarted) spin.start() else if (spin.isPaused) spin.resume()
            if (!pulse.isStarted) pulse.start() else if (pulse.isPaused) pulse.resume()
        } else {
            if (spin.isRunning) spin.pause()
            if (pulse.isRunning) pulse.pause()
            pulseView.alpha = 0f
        }
    }

    private fun startProgressUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                musicService?.let { svc ->
                    // Catches up the title/artist/art if this screen opened
                    // right as a "resume last played" was still starting up.
                    if (svc.getCurrentSong()?.id != lastBoundSongId) {
                        bindUiToCurrentSong()
                    }
                    val duration = svc.getDurationMs()
                    if (duration > 0) {
                        seekBar.max = duration
                        if (!userSeeking) seekBar.progress = svc.getCurrentPositionMs()
                        durationView.text = formatTime(duration.toLong())
                        if (!userSeeking) elapsedView.text = formatTime(svc.getCurrentPositionMs().toLong())
                    }
                    updatePlayPauseIcon()
                }
                volume.sync()
                handler.postDelayed(this, 500)
            }
        })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedView.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                musicService?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        spinAnimator?.cancel()
        pulseAnimator?.cancel()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
