package com.claude.musicplayer

import android.content.Context

/** Remembers the Songs tab's chosen sort order until the user picks a different one. */
object SortPreference {
    private const val PREFS = "sort_prefs"
    private const val KEY_SORT_ID = "songs_sort_id"

    fun save(context: Context, menuItemId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SORT_ID, menuItemId).apply()
    }

    /** Defaults to Title (A-Z) the very first time, before the user has ever chosen a sort. */
    fun load(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SORT_ID, R.id.sort_title_asc)
    }
}
