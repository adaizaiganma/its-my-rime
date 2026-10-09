package com.kingzcheung.xime

import android.content.Context

/**
 * Simplified → Taiwan Traditional conversion for text that bypasses Rime (voice input),
 * following OpenCC's s2tw.json: longest-match over STPhrases + STCharacters, then TWVariants.
 */
internal object ChineseConverter {
    private class Dictionary(val entries: Map<String, String>, val maxLength: Int)

    @Volatile private var stages: List<Dictionary>? = null

    fun toTaiwan(context: Context, text: String): String =
        stages(context).fold(text) { converted, dictionary -> convert(converted, dictionary) }

    private fun stages(context: Context): List<Dictionary> = stages ?: synchronized(this) {
        stages ?: listOf(
            load(context, "STPhrases.txt", "STCharacters.txt"),
            load(context, "TWVariants.txt"),
        ).also { stages = it }
    }

    private fun load(context: Context, vararg files: String): Dictionary {
        val entries = HashMap<String, String>()
        var maxLength = 1
        for (file in files) {
            context.assets.open("rime/opencc/$file").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val key = line.substring(0, tab)
                    // Phrases take precedence over single characters, as in OpenCC's dictionary group.
                    if (key in entries) return@forEach
                    val value = line.substring(tab + 1).substringBefore(' ')
                    if (value.isEmpty()) return@forEach
                    entries[key] = value
                    if (key.length > maxLength) maxLength = key.length
                }
            }
        }
        return Dictionary(entries, maxLength)
    }

    private fun convert(text: String, dictionary: Dictionary): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            var length = minOf(dictionary.maxLength, text.length - index)
            var match: String? = null
            while (length > 0) {
                match = dictionary.entries[text.substring(index, index + length)]
                if (match != null) break
                length--
            }
            if (match != null) {
                append(match)
                index += length
            } else {
                append(text[index])
                index++
            }
        }
    }
}
