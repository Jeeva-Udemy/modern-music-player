package com.claude.musicplayer

import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity

/**
 * Thin host: embeds the exact same SongsFragment used by the Songs tab,
 * configured for one playlist — so the whole search/sort/select/tap-to-play/
 * now-playing-highlight feature set is genuinely the same code, not a
 * re-implementation of it.
 */
class PlaylistSongsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
    }

    private lateinit var miniPlayer: MiniPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_host)

        val name = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: ""

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.playlistFragmentContainer, SongsFragment.forPlaylist(name))
                .commit()
        }

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

    override fun onDestroy() {
        miniPlayer.stop()
        super.onDestroy()
    }
}
