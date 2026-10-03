package com.kingzcheung.xime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class QuickPhrase(val code: String, val text: String)

/** Personal shortcuts backed by Rime Ice's existing custom_phrase translator. */
internal object QuickPhrases {
    private const val PREFS = "quick_phrases"
    private const val ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private const val MAX_TEXT_LENGTH = 200
    private val codePattern = Regex("[a-z]{1,20}")

    fun normalizeCode(code: String): String = code.trim().lowercase(Locale.ROOT)

    fun entries(context: Context): List<QuickPhrase> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ENTRIES, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList<QuickPhrase> {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val code = item.optString("code")
                val text = item.optString("text")
                if (codePattern.matches(code) && validText(text) && none { it.code == code }) {
                    add(QuickPhrase(code, text))
                }
            }
        }
    }.getOrDefault(emptyList())

    fun validationError(entries: List<QuickPhrase>, oldCode: String?, code: String, text: String): String? {
        if (!codePattern.matches(code)) return "縮寫只能使用 1–20 個小寫英文字母"
        if (!validText(text)) return "內容需為 1–$MAX_TEXT_LENGTH 字，且不能換行"
        if (code != oldCode && entries.any { it.code == code }) return "這個縮寫已存在"
        if (oldCode == null && entries.size >= MAX_ENTRIES) return "最多可保存 $MAX_ENTRIES 筆常用字"
        return null
    }

    fun save(context: Context, entries: List<QuickPhrase>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("code", entry.code).put("text", entry.text))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ENTRIES, array.toString()).apply()
    }

    /** Write only when content changes, so ordinary startup does not trigger a redeployment. */
    fun syncRimeFile(context: Context, sharedDir: File, userDir: File): Boolean {
        val personal = entries(context)
        val target = File(userDir, "custom_phrase.txt")
        if (personal.isEmpty() && !target.exists()) return false
        val base = File(sharedDir, "custom_phrase.txt").readText(Charsets.UTF_8).trimEnd()
        val content = buildString {
            append(base)
            if (personal.isNotEmpty()) {
                append("\n\n")
                personal.forEach { append(it.text).append('\t').append(it.code).append("\t1000\n") }
            } else append('\n')
        }
        if (target.exists() && target.readText(Charsets.UTF_8) == content) return false
        userDir.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        return true
    }

    private fun validText(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH &&
        text.none { it == '\n' || it == '\r' || it == '\t' }
}
