package com.claude.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NowPlayingActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastBoundSongId: Long = -1

    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var artView: ImageView
    private lateinit var playPauseButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var seekBar: SeekBar

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

        titleView = findViewById(R.id.npTitle)
        artistView = findViewById(R.id.npArtist)
        artView = findViewById(R.id.npArt)
        playPauseButton = findViewById(R.id.npPlayPause)
        shuffleButton = findViewById(R.id.npShuffle)
        repeatButton = findViewById(R.id.npRepeat)
        seekBar = findViewById(R.id.npSeekBar)

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
        findViewById<Button>(R.id.npAddToPlaylist).setOnClickListener {
            musicService?.getCurrentSong()?.let { song ->
                PlaylistDialogHelper.showAddToPlaylistDialog(this, song)
            }
        }

        Intent(this, MusicService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun bindUiToCurrentSong() {
        val song = musicService?.getCurrentSong() ?: return
        titleView.text = song.title
        artistView.text = song.artist
        if (song.id != lastBoundSongId) {
            lastBoundSongId = song.id
            artView.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            artView.setImageResource(R.drawable.ic_music_note)
            val bitmap = MusicRepository.loadAlbumArt(this, song.albumArtUri)
            if (bitmap != null) {
                artView.imageTintList = null
                artView.setImageBitmap(bitmap)
            }
        }
        updatePlayPauseIcon()
        updateToggleButtonStates()
    }

    private fun updateToggleButtonStates() {
        val service = musicService ?: return
        shuffleButton.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (service.shuffleEnabled) R.color.accent else R.color.textSecondary)
        )
        repeatButton.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (service.repeatEnabled) R.color.accent else R.color.textSecondary)
        )
    }

    private fun updatePlayPauseIcon() {
        val playing = musicService?.isPlaying() == true
        playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
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
                        seekBar.progress = svc.getCurrentPositionMs()
                    }
                    updatePlayPauseIcon()
                }
                handler.postDelayed(this, 500)
            }
        })
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                musicService?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
