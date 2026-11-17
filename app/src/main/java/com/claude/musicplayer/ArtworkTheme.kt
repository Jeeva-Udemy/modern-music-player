package com.claude.musicplayer

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors

/**
 * Follows whichever song is current (or was last played) and derives from
 * its album art:
 *  - a tiny pre-blurred copy for the animated background of every screen, and
 *  - accent colours picked to suit both the dark and the light theme, so
 *    buttons and sliders always match what's behind them.
 *
 * Screens subscribe with [bind]; one shared poller checks for song changes
 * only while something is listening.
 */
object ArtworkTheme {

    class State(
        val key: String?,
        val blurred: Bitmap?,
        val accentForDark: Int?,
        val accentForLight: Int?,
        val secondaryForDark: Int?,
        val secondaryForLight: Int?
    )

    fun interface Listener {
        fun onArtworkChanged(state: State)
    }

    private val empty = State(null, null, null, null, null, null)
    var state: State = empty
        private set

    private val listeners = LinkedHashSet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var requestedKey: String? = null
    private var hasRequested = false
    private var generation = 0

    private val poller = object : Runnable {
        override fun run() {
            checkForChange()
            if (listeners.isNotEmpty()) handler.postDelayed(this, 700)
        }
    }

    fun addListener(context: Context, listener: Listener) {
        appContext = context.applicationContext
        val wasEmpty = listeners.isEmpty()
        listeners.add(listener)
        listener.onArtworkChanged(state)
        if (wasEmpty) {
            handler.removeCallbacks(poller)
            handler.post(poller)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) handler.removeCallbacks(poller)
    }

    /** Subscribes for the lifetime of [owner] (removed automatically on destroy). */
    fun bind(owner: LifecycleOwner, context: Context, listener: Listener) {
        addListener(context, listener)
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) removeListener(listener)
        })
    }

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Accent for the given screen's theme — art-derived when possible, else the theme accent. */
    fun accentFor(context: Context): Int =
        (if (isNight(context)) state.accentForDark else state.accentForLight)
            ?: context.getColor(R.color.accent)

    fun secondaryFor(context: Context): Int =
        (if (isNight(context)) state.secondaryForDark else state.secondaryForLight)
            ?: context.getColor(R.color.accentSecondary)

    /** White or near-black, whichever reads better on top of [color]. */
    fun onColorFor(color: Int): Int {
        // Whichever of white / near-black contrasts more — e.g. dark text on
        // the soft lavender, white text on black or deep artwork colours.
        val dark = Color.parseColor("#1C1C2A")
        return if (ColorUtils.calculateContrast(Color.WHITE, ColorUtils.setAlphaComponent(color, 255)) >=
            ColorUtils.calculateContrast(dark, ColorUtils.setAlphaComponent(color, 255))
        ) Color.WHITE else dark
    }

    /** True while the current song's artwork is supplying the colours (vs. the theme fallback). */
    fun hasArtworkColours(context: Context): Boolean =
        (if (isNight(context)) state.accentForDark else state.accentForLight) != null

    /**
     * Background for dropdowns and dialogs: the screen's surface colour
     * washed with the artwork accent, a faint accent rim and rounded corners
     * — so popups look like part of the screen behind them.
     */
    fun panelBackground(context: Context, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val accent = accentFor(context)
        val surface = context.getColor(R.color.surfaceDark)
        val wash = ColorUtils.blendARGB(surface, accent, if (isNight(context)) 0.16f else 0.09f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(ColorUtils.setAlphaComponent(wash, 0xF7))
            setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 90))
        }
    }

    fun tintSeekBar(seekBar: SeekBar, color: Int) {
        val list = ColorStateList.valueOf(color)
        seekBar.progressTintList = list
        seekBar.thumbTintList = list
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 70))
    }

    private fun currentSong(context: Context): Song? {
        val path = MusicService.nowPlayingPath
        if (path != null) {
            MusicService.currentQueue.firstOrNull { it.path == path }?.let { return it }
        }
        return PlaybackStateStore.load(context)?.first
    }

    private fun checkForChange() {
        val context = appContext ?: return
        val key = currentSong(context)?.albumArtUri
        if (hasRequested && key == requestedKey) return
        hasRequested = true
        requestedKey = key
        val gen = ++generation
        if (key == null) {
            publish(empty)
            return
        }
        executor.execute {
            val result = try {
                compute(context, key)
            } catch (e: Throwable) {
                State(key, null, null, null, null, null)
            }
            handler.post { if (gen == generation) publish(result) }
        }
    }

    private fun publish(newState: State) {
        state = newState
        for (l in listeners.toList()) l.onArtworkChanged(newState)
    }

    private fun compute(context: Context, key: String): State {
        val art = MusicRepository.loadAlbumArt(context, key, 256)
            ?: return State(key, null, null, null, null, null)
        val blurred = blur(art)
        val palette = Palette.from(art).maximumColorCount(16).generate()

        val forDark = listOfNotNull(
            palette.lightVibrantSwatch, palette.vibrantSwatch,
            palette.lightMutedSwatch, palette.dominantSwatch
        ).firstOrNull()?.rgb?.let { tune(it, minL = 0.62f, maxL = 0.74f) }

        val forLight = listOfNotNull(
            palette.darkVibrantSwatch, palette.vibrantSwatch,
            palette.darkMutedSwatch, palette.dominantSwatch
        ).firstOrNull()?.rgb?.let { tune(it, minL = 0.34f, maxL = 0.44f) }

        return State(
            key, blurred, forDark, forLight,
            forDark?.let { shiftHue(it, 38f) },
            forLight?.let { shiftHue(it, 38f) }
        )
    }

    /** Clamps lightness for legibility and gives dull colours a bit of life. Greys stay null (theme accent wins). */
    private fun tune(color: Int, minL: Float, maxL: Float): Int? {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[1] < 0.12f) return null
        hsl[1] = hsl[1].coerceAtLeast(0.5f)
        hsl[2] = hsl[2].coerceIn(minL, maxL)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + degrees) % 360f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Shrinks the art to ~48px and box-blurs it. Drawn stretched to full
     * screen with bitmap filtering, that gives a smooth frosted backdrop on
     * every Android version without RenderEffect, and costs almost no memory.
     */
    private fun blur(src: Bitmap): Bitmap {
        val w = 48
        val h = (48f * src.height / src.width.coerceAtLeast(1)).toInt().coerceIn(16, 96)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(pixels, w, h, 3) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) src[line * w + j] else src[j * w + line]
                    a += c ushr 24 and 0xFF; rr += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
                    n++
                }
                val out = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            }
        }
    }
}
