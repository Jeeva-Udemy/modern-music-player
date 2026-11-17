package com.claude.musicplayer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.PopupWindowCompat

/**
 * The app's dropdown menu (sort, song ⋮, playlist ⋮). Built by hand instead
 * of PopupMenu so its background, check mark and text can take the colours
 * of the current artwork — PopupMenu's look is fixed by the theme.
 */
object GlassMenu {

    class Item(
        val title: String,
        val checked: Boolean = false,
        /** Shown in red — for Delete-type actions. */
        val destructive: Boolean = false
    )

    fun show(anchor: View, items: List<Item>, onSelect: (index: Int) -> Unit) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val accent = ArtworkTheme.accentFor(ctx)
        val textColor = ctx.getColor(R.color.textPrimary)
        val dangerColor = ctx.getColor(R.color.danger)
        val showChecks = items.any { it.checked }

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            minimumWidth = dp(200)
        }
        val content = ScrollView(ctx).apply {
            background = ArtworkTheme.panelBackground(ctx, radiusDp = 18f)
            clipToOutline = true
            addView(list)
        }

        val popup = PopupWindow(
            content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 14f * density
            isOutsideTouchable = true
        }

        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        items.forEachIndexed { index, item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(20), dp(12))
                setBackgroundResource(ripple)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    onSelect(index)
                }
            }
            if (showChecks) {
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(accent))
                    visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            }
            row.addView(TextView(ctx).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(
                    when {
                        item.destructive -> dangerColor
                        item.checked -> accent
                        else -> textColor
                    }
                )
                if (item.checked) setTypeface(typeface, Typeface.BOLD)
            })
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // Drop in with a small scale + fade from the anchor.
        content.alpha = 0f
        content.scaleY = 0.92f
        content.pivotY = 0f
        content.animate().alpha(1f).scaleY(1f).setDuration(160).start()

        PopupWindowCompat.showAsDropDown(popup, anchor, 0, dp(4), Gravity.END)
    }
}
