package com.kingzcheung.xime

import android.content.Context

internal object AppearanceSettings {
    const val PREFS_NAME = "appearance"
    const val DARK_MODE = "dark_mode"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(DARK_MODE, false)

    fun setDark(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(DARK_MODE, enabled).apply()
    }
}
