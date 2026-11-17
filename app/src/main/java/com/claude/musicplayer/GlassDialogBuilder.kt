package com.claude.musicplayer

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * MaterialAlertDialogBuilder whose background and buttons take the colours
 * of the current artwork, so every dialog in the app matches the screen
 * behind it. Use exactly like MaterialAlertDialogBuilder.
 */
class GlassDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {

    init {
        setBackground(ArtworkTheme.panelBackground(context, radiusDp = 26f))
    }

    override fun show(): AlertDialog {
        val dialog = super.show()
        tintButtons(dialog)
        return dialog
    }

    companion object {
        /** For dialogs built with create() + their own OnShowListener. */
        fun tintButtons(dialog: AlertDialog) {
            val ctx = dialog.context
            val accent = ArtworkTheme.accentFor(ctx)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(accent)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setTextColor(accent)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                ?.setTextColor(ctx.getColor(R.color.textSecondary))
        }
    }
}
