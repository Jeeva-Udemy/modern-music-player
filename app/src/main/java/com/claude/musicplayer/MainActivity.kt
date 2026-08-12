package com.claude.musicplayer

import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var miniPlayer: MiniPlayerController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* fragments refresh themselves in onResume once granted */ }

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

        miniPlayer = MiniPlayerController(
            context = this,
            miniPlayerBar = findViewById(R.id.miniPlayerBar),
            titleView = findViewById(R.id.nowPlayingTitle),
            artistView = findViewById(R.id.nowPlayingArtist),
            artView = findViewById<ImageView>(R.id.miniArt),
            playPauseButton = findViewById(R.id.playPauseButton),
            prevButton = findViewById(R.id.prevButton),
            nextButton = findViewById(R.id.nextButton),
            seekBar = findViewById<SeekBar>(R.id.miniSeekBar)
        )
        miniPlayer.start()
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
        miniPlayer.stop()
        super.onDestroy()
    }
}
