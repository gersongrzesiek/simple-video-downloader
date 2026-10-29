package com.simplevideodownloader.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.simplevideodownloader.R
import com.simplevideodownloader.databinding.ActivityPlayerBinding

/** Reproductor integrado para los vídeos descargados. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var position = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = intent.getStringExtra(EXTRA_TITLE)
        position = savedInstanceState?.getLong(KEY_POSITION) ?: 0L
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        val uri = intent.data ?: run {
            finish()
            return
        }
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }
            })
            exo.seekTo(position)
            exo.playWhenReady = true
            exo.prepare()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            position = it.currentPosition
            it.release()
        }
        player = null
        binding.playerView.player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, player?.currentPosition ?: position)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        private const val KEY_POSITION = "position"
    }
}
