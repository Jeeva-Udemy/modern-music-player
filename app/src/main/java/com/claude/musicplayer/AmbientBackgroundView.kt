package com.claude.musicplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen animated backdrop shared by every screen:
 *  - the current song's album art, blurred, slowly drifting/zooming/tilting,
 *  - three glowing colour orbs (tinted from the art) moving through depth,
 *  - a field of soft particles floating upward with parallax,
 *  - and a scrim so text on top stays readable in both themes.
 *
 * Crossfades whenever the song (and therefore the artwork) changes.
 * Use [attach] right after setContentView; the layout's root must be transparent.
 */
class AmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        fun attach(activity: Activity, strong: Boolean = false): AmbientBackgroundView {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = AmbientBackgroundView(activity).also { it.strong = strong }
            content.addView(
                view, 0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            return view
        }

        private const val FRAME_MS = 33L
        private const val FADE_MS = 900f
    }

    /** Stronger artwork presence — used by the Now Playing screen. */
    var strong: Boolean = false

    private val night = ArtworkTheme.isNight(context)
    private val baseColor = context.getColor(R.color.ambientBase)
    private val scrimTop = context.getColor(R.color.ambientScrimTop)
    private val scrimBottom = context.getColor(R.color.ambientScrimBottom)
    private val density = resources.displayMetrics.density

    private var art: Bitmap? = null
    private var previousArt: Bitmap? = null
    private var fadeStart = 0L

    private var orbColors = computeOrbColors()
    private var previousOrbColors = orbColors.copyOf()

    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val orbMatrix = Matrix()
    private val artMatrix = Matrix()
    private val scrimPaint = Paint()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val startTime = SystemClock.uptimeMillis()
    private var lastFrameTime = startTime

    private class Particle(var x: Float, var y: Float, val z: Float, val speed: Float, val drift: Float)
    private val particles = Array(18) {
        Particle(
            x = Random.nextFloat(), y = Random.nextFloat(),
            z = 0.2f + Random.nextFloat() * 0.8f,
            speed = 0.010f + Random.nextFloat() * 0.025f,
            drift = Random.nextFloat() * 6.28f
        )
    }

    private val animationsOn: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled() else true

    private val listener = ArtworkTheme.Listener { state ->
        if (state.blurred !== art) {
            previousArt = art
            art = state.blurred
            previousOrbColors = currentOrbColors()
            orbColors = computeOrbColors()
            fadeStart = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    /**
     * Glows take the artwork's colours when there is artwork; otherwise the
     * theme's own soft glows (lavender/periwinkle on slate, peach/sky/lilac
     * on cream) — the plain fallback accent (black in light mode) would make
     * murky orbs.
     */
    private fun computeOrbColors(): IntArray =
        if (ArtworkTheme.hasArtworkColours(context)) intArrayOf(
            ArtworkTheme.accentFor(context),
            ArtworkTheme.secondaryFor(context),
            context.getColor(R.color.ambientOrb3)
        ) else intArrayOf(
            context.getColor(R.color.ambientOrb1),
            context.getColor(R.color.ambientOrb2),
            context.getColor(R.color.ambientOrb3)
        )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ArtworkTheme.addListener(context, listener)
    }

    override fun onDetachedFromWindow() {
        ArtworkTheme.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            lastFrameTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(scrimTop, ColorUtils.setAlphaComponent(scrimBottom, Color.alpha(scrimBottom) / 2), scrimBottom),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun fadeProgress(): Float =
        ((SystemClock.uptimeMillis() - fadeStart) / FADE_MS).coerceIn(0f, 1f)

    private fun currentOrbColors(): IntArray {
        val f = fadeProgress()
        return IntArray(3) { ColorUtils.blendARGB(previousOrbColors[it], orbColors[it], f) }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val t = if (animationsOn) (now - startTime) / 1000f else 0f
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0f, 0.1f)
        lastFrameTime = now
        val fade = fadeProgress()

        canvas.drawColor(baseColor)

        // 1) Blurred album art, slowly zooming/rotating (Ken Burns with a tilt).
        val artAlpha = when {
            strong -> if (night) 235 else 190
            else -> if (night) 175 else 120
        }
        previousArt?.let { if (fade < 1f) drawArt(canvas, it, t, (artAlpha * (1f - fade)).toInt()) }
        art?.let { drawArt(canvas, it, t, (artAlpha * fade).toInt()) }

        // 2) Glowing orbs moving through "depth" — nearer ones get bigger and brighter.
        val colors = currentOrbColors()
        val orbBaseAlpha = if (night) 0.42f else 0.30f
        val extent = max(w, h)
        for (i in 0 until 3) {
            val z = 0.5f + 0.5f * sin(t * 0.21f + i * 2.1f)
            val scale = 0.7f + 0.55f * z
            val cx = w * (0.5f + 0.40f * sin(t * 0.13f * (i + 1) + i))
            val cy = h * (0.45f + 0.38f * cos(t * 0.10f * (i + 1.3f) + i * 1.7f))
            val r = extent * 0.42f * scale
            orbMatrix.reset()
            orbMatrix.setScale(r, r)
            orbMatrix.postTranslate(cx, cy)
            orbPaint.shader.setLocalMatrix(orbMatrix)
            orbPaint.colorFilter = PorterDuffColorFilter(colors[i], PorterDuff.Mode.SRC_IN)
            orbPaint.alpha = (255 * orbBaseAlpha * (0.5f + 0.5f * z)).toInt()
            canvas.drawCircle(cx, cy, r, orbPaint)
        }

        // 3) Readability scrim.
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // 4) Particles drifting upward; closer (higher z) = larger, faster, brighter.
        val particleColor = if (night) Color.WHITE else colors[0]
        for (p in particles) {
            if (animationsOn) {
                p.y -= p.speed * p.z * dt * 3f
                if (p.y < -0.05f) { p.y = 1.05f; p.x = Random.nextFloat() }
            }
            val px = (p.x + 0.02f * sin(t * 0.6f + p.drift)) * w
            val py = p.y * h
            particlePaint.color = ColorUtils.setAlphaComponent(particleColor, (40 + 110 * p.z).toInt())
            canvas.drawCircle(px, py, (1.2f + 2.8f * p.z) * density, particlePaint)
        }

        if (animationsOn && windowVisibility == VISIBLE) {
            postInvalidateDelayed(FRAME_MS)
        } else if (fade < 1f) {
            postInvalidateDelayed(FRAME_MS)
        }
    }

    private fun drawArt(canvas: Canvas, bmp: Bitmap, t: Float, alpha: Int) {
        if (alpha <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cover = max(w / bmp.width, h / bmp.height)
        val zoom = 1.35f + 0.08f * sin(t * 0.25f)
        val s = cover * zoom
        artMatrix.reset()
        artMatrix.setTranslate(-bmp.width / 2f, -bmp.height / 2f)
        artMatrix.postScale(s, s)
        artMatrix.postRotate(6f * sin(t * 0.07f))
        artMatrix.postTranslate(
            w / 2f + w * 0.04f * sin(t * 0.11f),
            h / 2f + h * 0.03f * cos(t * 0.09f)
        )
        artPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bmp, artMatrix, artPaint)
    }
}
