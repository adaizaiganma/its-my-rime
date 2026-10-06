package com.kingzcheung.xime

/** Shared colours for the Compose settings screen and the native IME panels. */
internal data class UiPalette(
    val canvas: Int,
    val surface: Int,
    val inset: Int,
    val key: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val action: Int,
    val onAction: Int,
    val outline: Int,
    val keyPressed: Int,
    val insetPressed: Int,
    val actionPressed: Int,
    val success: Int,
    val warning: Int,
    val error: Int
)

internal object UiTheme {
    val light = UiPalette(
        canvas = 0xFFFAF9F5.toInt(), surface = 0xFFF5F0E8.toInt(),
        inset = 0xFFEFE9DE.toInt(), key = 0xFFFAF9F5.toInt(),
        ink = 0xFF141413.toInt(), muted = 0xFF6C6A64.toInt(),
        accent = 0xFFCC785C.toInt(), action = 0xFFA9583E.toInt(),
        onAction = 0xFFFAF9F5.toInt(), outline = 0xFFE6DFD8.toInt(),
        keyPressed = 0xFFE8E0D2.toInt(), insetPressed = 0xFFDDD3C4.toInt(),
        actionPressed = 0xFF8E4631.toInt(), success = 0xFF337B4D.toInt(),
        warning = 0xFF946A22.toInt(), error = 0xFFC64545.toInt()
    )
    val dark = UiPalette(
        canvas = 0xFF181715.toInt(), surface = 0xFF1F1E1B.toInt(),
        inset = 0xFF302D28.toInt(), key = 0xFF252320.toInt(),
        ink = 0xFFFAF9F5.toInt(), muted = 0xFFB5B0A7.toInt(),
        accent = 0xFFE0A38D.toInt(), action = 0xFFA9583E.toInt(),
        onAction = 0xFFFAF9F5.toInt(), outline = 0xFF4B463E.toInt(),
        keyPressed = 0xFF3B3731.toInt(), insetPressed = 0xFF474036.toInt(),
        actionPressed = 0xFF8E4631.toInt(), success = 0xFF82C996.toInt(),
        warning = 0xFFE8A55A.toInt(), error = 0xFFE28E87.toInt()
    )

    fun palette(darkMode: Boolean): UiPalette = if (darkMode) dark else light
}
