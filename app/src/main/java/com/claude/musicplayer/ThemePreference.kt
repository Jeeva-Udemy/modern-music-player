package com.claude.musicplayer

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate

/** Dark / Light / System theme choice, remembered across app restarts. */
object ThemePreference {
    private const val PREFS = "theme_prefs"
    private const val KEY_MODE = "theme_mode"

    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    const val MODE_SYSTEM = "system"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_DARK) ?: MODE_DARK

    /** Call once at startup (Application.onCreate) so every screen opens in the saved mode. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(load(context)))
    }

    fun set(context: Context, mode: String) {
        if (mode == load(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).commit()
        // Recreates open activities so the new palette applies immediately.
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
    }

    fun iconFor(mode: String): Int = when (mode) {
        MODE_LIGHT -> R.drawable.ic_light_mode
        MODE_SYSTEM -> R.drawable.ic_brightness_auto
        else -> R.drawable.ic_dark_mode
    }

    fun showPicker(context: Context, anchor: View) {
        val popup = PopupMenu(context, anchor)
        val current = load(context)
        val options = listOf(MODE_DARK to "Dark", MODE_LIGHT to "Light", MODE_SYSTEM to "System default")
        options.forEachIndexed { index, (mode, label) ->
            popup.menu.add(0, index, index, label).apply {
                isCheckable = true
                isChecked = mode == current
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            set(context, options[item.itemId].first)
            true
        }
        popup.show()
    }

    private fun toNightMode(mode: String): Int = when (mode) {
        MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }
}
