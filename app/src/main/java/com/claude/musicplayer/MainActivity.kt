package com.claude.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var bound = false

    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var miniArt: ImageView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var miniPlayerBar: View
    private val handler = Handler(Looper.getMainLooper())
    private var lastArtSongId: Long = -1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* fragments refresh themselves in onResume once granted */ }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNeededPermissions()

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        viewPager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Songs"
                1 -> "Playlists"
                2 -> "Folders"
                else -> "Duplicates"
            }
        }.attach()

        nowPlayingTitle = findViewById(R.id.nowPlayingTitle)
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist)
        miniArt = findViewById(R.id.miniArt)
        playPauseButton = findViewById(R.id.playPauseButton)
        seekBar = findViewById(R.id.miniSeekBar)
        miniPlayerBar = findViewById(R.id.miniPlayerBar)

        // Tapping the mini player (outside the buttons) opens the full-screen player.
        miniPlayerBar.setOnClickListener {
            if (musicService?.getCurrentSong() != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }

        playPauseButton.setOnClickListener {
            musicService?.let {
                if (it.isPlaying()) it.pause() else it.resume()
                updatePlayPauseIcon()
            }
        }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener { musicService?.playNext() }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener { musicService?.playPrevious() }

        Intent(this, MusicService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
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
                    val song = svc.getCurrentSong()
                    if (song != null) {
                        nowPlayingTitle.text = song.title
                        nowPlayingArtist.text = song.artist
                        if (song.id != lastArtSongId) {
                            lastArtSongId = song.id
                            miniArt.setImageResource(R.drawable.ic_music_note)
                            val bitmap = MusicRepository.loadAlbumArt(this@MainActivity, song.albumArtUri)
                            if (bitmap != null) miniArt.setImageBitmap(bitmap)
                        }
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

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
