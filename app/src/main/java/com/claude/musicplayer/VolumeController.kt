package com.claude.musicplayer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.SeekBar

/**
 * Binds a SeekBar (and an optional speaker icon that toggles mute) to the
 * device's media volume, so the slider and the hardware volume keys always
 * agree. Call [sync] periodically to pick up changes made with the keys.
 */
class VolumeController(
    context: Context,
    private val seekBar: SeekBar,
    private val icon: ImageView? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stream = AudioManager.STREAM_MUSIC
    private val maxVolume = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
    private var userDragging = false
    private var lastNonZero = (maxVolume / 2).coerceAtLeast(1)

    init {
        seekBar.max = maxVolume
        if (audioManager.isVolumeFixed) {
            seekBar.isEnabled = false
            icon?.isEnabled = false
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setVolume(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { userDragging = false }
        })
        icon?.setOnClickListener { toggleMute() }
        sync()
    }

    fun sync() {
        if (userDragging) return
        val current = audioManager.getStreamVolume(stream)
        if (seekBar.progress != current) seekBar.progress = current
        if (current > 0) lastNonZero = current
        updateIcon(current)
    }

    private fun setVolume(value: Int) {
        try {
            audioManager.setStreamVolume(stream, value.coerceIn(0, maxVolume), 0)
        } catch (e: SecurityException) {
            // Some devices block volume changes while Do Not Disturb is on.
        }
        if (value > 0) lastNonZero = value
        updateIcon(value)
    }

    private fun toggleMute() {
        val current = audioManager.getStreamVolume(stream)
        val target = if (current > 0) 0 else lastNonZero
        setVolume(target)
        seekBar.progress = target
    }

    private fun updateIcon(value: Int) {
        icon?.setImageResource(
            when {
                value == 0 -> R.drawable.ic_volume_off
                value < maxVolume / 2 -> R.drawable.ic_volume_down
                else -> R.drawable.ic_volume_up
            }
        )
    }

    companion object {
        /** A small glass volume slider that pops up just above [anchor]. */
        fun showPopup(anchor: View, accent: Int) {
            val context = anchor.context
            val content = LayoutInflater.from(context).inflate(R.layout.popup_volume, null)
            val seek = content.findViewById<SeekBar>(R.id.popupVolumeSeekBar)
            val icon = content.findViewById<ImageView>(R.id.popupVolumeIcon)
            ArtworkTheme.tintSeekBar(seek, accent)
            val controller = VolumeController(context, seek, icon)

            val popup = PopupWindow(
                content,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popup.elevation = 12f * context.resources.displayMetrics.density
            popup.isOutsideTouchable = true

            val handler = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    controller.sync()
                    handler.postDelayed(this, 400)
                }
            }
            handler.post(poll)
            popup.setOnDismissListener { handler.removeCallbacks(poll) }

            content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            val margin = (8 * context.resources.displayMetrics.density).toInt()
            val x = (loc[0] + anchor.width - content.measuredWidth).coerceAtLeast(margin)
            val y = (loc[1] - content.measuredHeight - margin).coerceAtLeast(margin)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }
}
