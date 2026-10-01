package com.kingzcheung.xime

import android.content.Context

internal object SpaceCursorSettings {
    private const val PREFS_NAME = "input_modes"
    private const val SENSITIVITY = "space_cursor_sensitivity"
    const val DEFAULT = 3

    fun read(context: Context): Int = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(SENSITIVITY, DEFAULT).coerceIn(1, 5)

    fun write(context: Context, level: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(SENSITIVITY, level.coerceIn(1, 5)).apply()
    }

    fun stepDp(level: Int): Int = when (level.coerceIn(1, 5)) {
        1 -> 48
        2 -> 40
        3 -> 32
        4 -> 24
        else -> 16
    }
}
