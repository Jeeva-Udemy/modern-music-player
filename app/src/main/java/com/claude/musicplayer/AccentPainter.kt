package com.claude.musicplayer

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout

/**
 * Re-colours a screen with the accent taken from the current song's artwork,
 * so buttons, chips, checkboxes etc. always match the background instead of
 * using one fixed purple.
 *
 * Views opt in through android:tag in their layout:
 *  - "accent_fill"  — filled button/card: accent background, readable text/icon on top
 *  - "accent_text"  — accent text colour
 *  - "accent_tint"  — accent icon / checkbox / slider tint
 *  - "accent2_text", "accent2_tint" — same with the secondary (companion) accent
 * Pull-to-refresh spinners and text fields are recoloured automatically.
 *
 * Screens call [bind] once; it repaints whenever the song (artwork) changes.
 */
object AccentPainter {
    const val FILL = "accent_fill"
    const val TEXT = "accent_text"
    const val TINT = "accent_tint"
    const val TEXT2 = "accent2_text"
    const val TINT2 = "accent2_tint"

    /** Paints [root] now and again on every artwork change until [owner] is destroyed. */
    fun bind(owner: androidx.lifecycle.LifecycleOwner, root: View) {
        ArtworkTheme.bind(owner, root.context) { paint(root) }
    }

    fun paint(root: View) {
        val ctx = root.context
        val accent = ArtworkTheme.accentFor(ctx)
        val second = ArtworkTheme.secondaryFor(ctx)
        val onAccent = ArtworkTheme.onColorFor(accent)
        walk(root) { v -> paintView(v, accent, second, onAccent) }
    }

    private fun walk(v: View, action: (View) -> Unit) {
        action(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), action)
            }
        }
    }

    private fun csl(color: Int) = ColorStateList.valueOf(color)

    private fun paintView(v: View, accent: Int, second: Int, onAccent: Int) {
        when (v) {
            is SwipeRefreshLayout -> v.setColorSchemeColors(accent, second)
            is TextInputLayout -> {
                v.setBoxStrokeColor(accent)
                v.setHintTextColor(csl(accent))
                v.setEndIconTintList(csl(accent))
            }
        }
        when (v.tag as? String) {
            FILL -> {
                if (v is MaterialButton) {
                    v.backgroundTintList = csl(accent)
                    v.setTextColor(onAccent)
                    v.iconTint = csl(onAccent)
                    v.strokeColor = csl(ColorUtils.setAlphaComponent(onAccent, 60))
                } else if (v is MaterialCardView) {
                    v.setCardBackgroundColor(accent)
                } else {
                    v.backgroundTintList = csl(accent)
                }
            }
            TEXT -> (v as? TextView)?.setTextColor(accent)
            TEXT2 -> (v as? TextView)?.setTextColor(second)
            TINT -> tint(v, accent)
            TINT2 -> tint(v, second)
        }
    }

    private fun tint(v: View, color: Int) {
        when (v) {
            is CompoundButton -> CompoundButtonCompat.setButtonTintList(v, csl(color))
            is MaterialButton -> v.iconTint = csl(color)
            is ImageView -> ImageViewCompat.setImageTintList(v, csl(color))
            is SeekBar -> ArtworkTheme.tintSeekBar(v, color)
        }
    }
}
