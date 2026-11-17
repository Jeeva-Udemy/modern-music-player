package com.claude.musicplayer

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Dark / Light theme, chosen with the sun/moon button on the home screen and
 * remembered across restarts. Dark is the default.
 */
object ThemePreference {
    private const val PREFS = "theme_prefs"
    private const val KEY_MODE = "theme_mode"

    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    /** Anything other than "light" (including the old "system" value) means dark. */
    fun load(context: Context): String =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_DARK) == MODE_LIGHT
        ) MODE_LIGHT else MODE_DARK

    fun isDark(context: Context): Boolean = load(context) == MODE_DARK

    /** Call once at startup (Application.onCreate) so every screen opens in the saved mode. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(load(context)))
    }

    /** Switches Dark ⇄ Light. Open screens are recreated in the new palette. */
    fun toggle(context: Context) {
        val next = if (isDark(context)) MODE_LIGHT else MODE_DARK
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, next).commit()
        AppCompatDelegate.setDefaultNightMode(toNightMode(next))
    }

    /** Moon while in Dark mode, sun while in Light mode. */
    fun iconFor(context: Context): Int =
        if (isDark(context)) R.drawable.ic_dark_mode else R.drawable.ic_light_mode

    private fun toNightMode(mode: String): Int =
        if (mode == MODE_LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
}
