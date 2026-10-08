package com.kingzcheung.xime

import android.content.Context

/** GIPHY SDK key entered on the settings page; the build-time key is only a fallback. */
internal object GiphySettings {
    private const val PREFS_NAME = "input_modes"
    private const val API_KEY = "giphy_api_key"
    private val keyPattern = Regex("[A-Za-z0-9_-]+")

    fun userKey(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(API_KEY, "").orEmpty()

    fun apiKey(context: Context): String = userKey(context).ifBlank { BuildConfig.GIPHY_SDK_KEY }

    fun isValid(key: String): Boolean = keyPattern.matches(key)

    fun write(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(API_KEY, key.trim()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(API_KEY).apply()
    }

    /** Shows only the tail so the key isn't readable over someone's shoulder. */
    fun masked(key: String): String = if (key.length <= 4) "••••" else "••••" + key.takeLast(4)
}
