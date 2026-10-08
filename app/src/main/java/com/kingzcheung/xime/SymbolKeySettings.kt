package com.kingzcheung.xime

import android.content.Context

/** Symbols on the shortcut key beside the space bar: tap, swipe up, and the long-press menu. */
internal data class SymbolKeyConfig(val primary: String, val secondary: String, val menu: List<String>)

internal object SymbolKeySettings {
    private const val PREFS_NAME = "input_modes"
    private const val PRIMARY = "symbol_key_primary"
    private const val SECONDARY = "symbol_key_secondary"
    private const val MENU = "symbol_key_menu"
    // The long-press popup is 48 dp per item, so more than eight no longer fits a phone keyboard.
    const val MAX_MENU = 8
    private const val MAX_SYMBOL_CODE_POINTS = 4

    // Half-width defaults follow the keyboard's full/half-width toggle, matching the original key.
    val DEFAULT = SymbolKeyConfig(",", ".", listOf(",", "."))

    fun read(context: Context): SymbolKeyConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val primary = prefs.getString(PRIMARY, null)?.takeIf(::isValidSymbol) ?: DEFAULT.primary
        val secondary = prefs.getString(SECONDARY, null)?.takeIf(::isValidSymbol) ?: DEFAULT.secondary
        val menu = prefs.getString(MENU, null)?.let(::parseMenu)
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_MENU && it.all(::isValidSymbol) } ?: DEFAULT.menu
        return SymbolKeyConfig(primary, secondary, menu)
    }

    fun write(context: Context, config: SymbolKeyConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PRIMARY, config.primary)
            .putString(SECONDARY, config.secondary)
            .putString(MENU, config.menu.joinToString(" "))
            .apply()
    }

    fun isSymbolKeyPreference(key: String): Boolean = key == PRIMARY || key == SECONDARY || key == MENU

    /**
     * Folds full-width punctuation back to half-width, so symbols typed on the settings page with this
     * keyboard in Chinese mode still follow the full/half-width toggle instead of staying full-width.
     */
    fun normalize(value: String): String = buildString(value.length) {
        value.trim().forEach { char ->
            append(when (char) {
                '，' -> ','
                '。' -> '.'
                in '！'..'～' -> (char.code - 0xFEE0).toChar()
                else -> char
            })
        }
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PRIMARY).remove(SECONDARY).remove(MENU).apply()
    }

    fun parseMenu(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter(String::isNotEmpty).map(::normalize)

    fun menuText(menu: List<String>): String = menu.joinToString(" ")

    fun isValidSymbol(value: String): Boolean =
        value.isNotEmpty() && value.none(Char::isWhitespace) &&
            value.codePointCount(0, value.length) <= MAX_SYMBOL_CODE_POINTS

    fun isValidMenu(menuText: String): Boolean = parseMenu(menuText).let { menu ->
        menu.isNotEmpty() && menu.size <= MAX_MENU && menu.all(::isValidSymbol)
    }

    /** Returns a message for the settings page, or null when the three fields can be saved. */
    fun validationError(primary: String, secondary: String, menuText: String): String? {
        val menu = parseMenu(menuText)
        return when {
            primary.isBlank() -> "請填入主要符號"
            !isValidSymbol(normalize(primary)) -> "主要符號限 1 個符號（最多 $MAX_SYMBOL_CODE_POINTS 個字元），不可含空白"
            secondary.isBlank() -> "請填入副符號"
            !isValidSymbol(normalize(secondary)) -> "副符號限 1 個符號（最多 $MAX_SYMBOL_CODE_POINTS 個字元），不可含空白"
            menu.isEmpty() -> "長按選單至少需要 1 個符號"
            menu.size > MAX_MENU -> "長按選單最多 $MAX_MENU 個符號"
            !menu.all(::isValidSymbol) -> "長按選單的每個符號最多 $MAX_SYMBOL_CODE_POINTS 個字元，請用空白分隔"
            else -> null
        }
    }
}
