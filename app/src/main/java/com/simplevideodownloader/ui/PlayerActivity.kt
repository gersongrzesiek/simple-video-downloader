package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.simplevideodownloader.R
import com.simplevideodownloader.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reproductor integrado para los vídeos descargados. Gira solo según el vídeo (horizontal o vertical) y
 * tiene gestos: deslizar a los lados avanza o atrasa; arriba/abajo en la mitad derecha cambia el volumen
 * y en la izquierda el brillo.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var position = 0L
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val name = intent.getStringExtra(EXTRA_TITLE)?.let(::displayName)
        title = name
        position = savedInstanceState?.getLong(KEY_POSITION) ?: 0L
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        binding.playerView.findViewById<TextView>(R.id.player_title)?.text = name
        binding.playerView.findViewById<ImageButton>(R.id.player_back)?.setOnClickListener { finish() }
        setupGestures()
    }

    override fun onStart() {
        super.onStart()
        val uri = intent.data ?: run {
            finish()
            return
        }
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build().also { exo ->
                binding.playerView.player = exo
                exo.setMediaItem(MediaItem.fromUri(uri))
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) finish()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) = matchOrientation(videoSize)
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

    /** Pantalla horizontal para vídeos anchos y vertical para los altos. */
    private fun matchOrientation(size: VideoSize) {
        if (size.width <= 0 || size.height <= 0) return
        val rotated = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
        val width = if (rotated) size.height else size.width
        val height = if (rotated) size.width else size.height
        requestedOrientation = if (width > height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // --- Gestos ---

    private enum class Gesture { NONE, SEEK, VOLUME, BRIGHTNESS }

    private var gesture = Gesture.NONE
    private var startPosition = 0L
    private var seekTarget = -1L
    /** Volumen fino de 0 a 100: el del sistema da el grueso y el del reproductor afina entre sus pasos. */
    private var startVolume = 0
    private var startBrightness = 0.5f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                gesture = Gesture.NONE
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val view = binding.playerView
                if (view.isControllerFullyVisible) view.hideController() else view.showController()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = start.y - e2.y // hacia arriba es positivo
                if (gesture == Gesture.NONE) {
                    if (abs(dx) < GESTURE_SLOP_PX && abs(dy) < GESTURE_SLOP_PX) return false
                    beginGesture(
                        when {
                            abs(dx) > abs(dy) -> Gesture.SEEK
                            start.x > binding.playerView.width / 2f -> Gesture.VOLUME
                            else -> Gesture.BRIGHTNESS
                        }
                    )
                }
                when (gesture) {
                    Gesture.SEEK -> updateSeek(dx)
                    Gesture.VOLUME -> updateVolume(dy)
                    Gesture.BRIGHTNESS -> updateBrightness(dy)
                    Gesture.NONE -> Unit
                }
                return true
            }
        })
        binding.playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }
    }

    private fun beginGesture(type: Gesture) {
        gesture = type
        val exo = player
        startPosition = exo?.currentPosition ?: 0L
        seekTarget = -1L
        startVolume = currentVolume()
        startBrightness = window.attributes.screenBrightness.takeIf { it >= 0 }
            ?: runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        if (type != Gesture.SEEK) binding.playerView.hideController()
    }

    /** Todo el ancho de la pantalla equivale a 90 segundos (o al vídeo entero si es más corto). */
    private fun updateSeek(dx: Float) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        val range = minOf(duration, SEEK_RANGE_MS)
        val target = (startPosition + dx / binding.playerView.width * range).toLong().coerceIn(0, duration)
        seekTarget = target
        exo.seekTo(target)
        val delta = (target - startPosition) / 1000
        val sign = if (delta >= 0) "+" else "-"
        showIndicator(
            "$sign${DateUtils.formatElapsedTime(abs(delta))}\n" +
                "${DateUtils.formatElapsedTime(target / 1000)} / ${DateUtils.formatElapsedTime(duration / 1000)}"
        )
    }

    private fun currentVolume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val system = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (system * 100f / max * (player?.volume ?: 1f)).roundToInt().coerceIn(0, 100)
    }

    /**
     * Deslizar toda la altura de la pantalla va de 0 a 100, de uno en uno. El sistema solo tiene unos 15 pasos,
     * así que se sube al paso necesario y el reproductor baja su propio volumen hasta el valor exacto.
     */
    private fun updateVolume(dy: Float) {
        val level = (startVolume + dy / binding.playerView.height * 100 * 1.2f).roundToInt().coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val step = kotlin.math.ceil(level * max / 100f).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        player?.volume = if (step == 0) 0f else (level / 100f) / (step.toFloat() / max)
        showIndicator(getString(R.string.gesture_volume, level))
    }

    private fun updateBrightness(dy: Float) {
        val brightness = (startBrightness + dy / binding.playerView.height * 1.2f).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        showIndicator(getString(R.string.gesture_brightness, (brightness * 100).roundToInt()))
    }

    private fun endGesture() {
        if (gesture == Gesture.NONE) return
        gesture = Gesture.NONE
        binding.gestureIndicator.postDelayed({ if (gesture == Gesture.NONE) binding.gestureIndicator.isVisible = false }, 500)
    }

    private fun showIndicator(text: String) {
        binding.gestureIndicator.text = text
        binding.gestureIndicator.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_TITLE = "title"
        private const val KEY_POSITION = "position"
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_RANGE_MS = 90_000L
        private const val GESTURE_SLOP_PX = 24f
    }
}
