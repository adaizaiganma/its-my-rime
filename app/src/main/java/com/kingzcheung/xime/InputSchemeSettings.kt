package com.kingzcheung.xime

import android.content.Context

/** Which Rime schema the Chinese keyboard uses: 霧凇拼音 or 大千式注音. */
internal enum class InputScheme(val schemaId: String, val label: String) {
    PINYIN("rime_ice", "拼音"),
    ZHUYIN("bopomofo_ice", "注音");

    companion object {
        fun fromId(id: String?): InputScheme = entries.firstOrNull { it.schemaId == id } ?: PINYIN
    }
}

internal object InputSchemeSettings {
    private const val PREFS_NAME = "input_modes"
    const val KEY = "input_scheme"

    fun read(context: Context): InputScheme = InputScheme.fromId(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun write(context: Context, scheme: InputScheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY, scheme.schemaId).apply()
    }
}
