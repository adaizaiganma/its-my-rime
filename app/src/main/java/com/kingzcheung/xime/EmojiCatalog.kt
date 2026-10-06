package com.kingzcheung.xime

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import java.util.concurrent.Executors

data class EmojiCategory(val name: String, val icon: String, val emoji: List<String>)
data class EmojiVariantChoices(val base: String, val choices: List<String>)

/** Offline Unicode Emoji 18.0 picker, filtered to glyphs this device can draw. */
object EmojiCatalog {
    private const val PREFS = "emoji_picker"
    private const val RECENT = "recent"
    private const val MAX_RECENT = 32
    private const val CATALOG_ASSET = "emoji/emoji-18.0.txt"
    private const val VARIANTS_ASSET = "emoji/variants-18.0.txt"

    private data class CatalogData(
        val categories: List<EmojiCategory>,
        val variants: Map<String, EmojiVariantChoices>
    )

    @Volatile private var cachedData: CatalogData? = null
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "emoji-catalog").apply { isDaemon = true }
    }
    private var loading = false
    private val readyCallbacks = mutableListOf<() -> Unit>()

    fun isReady(): Boolean = cachedData != null

    /** Asset parsing and thousands of hasGlyph calls must not block a UI frame. */
    fun prepare(context: Context, onReady: (() -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(this) {
            if (cachedData != null) {
                onReady?.let { callback -> main.post { callback() } }
                return
            }
            onReady?.let(readyCallbacks::add)
            if (loading) return
            loading = true
        }
        loader.execute {
            val loaded = load(app)
            val callbacks = synchronized(this) {
                cachedData = loaded
                loading = false
                readyCallbacks.toList().also { readyCallbacks.clear() }
            }
            callbacks.forEach { callback -> main.post { callback() } }
        }
    }

    private fun data(context: Context): CatalogData = cachedData ?: synchronized(this) {
        cachedData ?: load(context.applicationContext).also { cachedData = it }
    }

    fun categories(context: Context): List<EmojiCategory> = data(context).categories

    fun variants(context: Context, emoji: String): EmojiVariantChoices? = data(context).variants[emoji]

    private fun load(context: Context): CatalogData = runCatching {
        val paint = Paint()
        val supportedCategories = context.assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filterNot { it.startsWith('#') || it.isBlank() }.map { line ->
                val parts = line.split('\t', limit = 3)
                require(parts.size == 3) { "Invalid emoji catalog entry" }
                EmojiCategory(parts[0], parts[1], parts[2].split(' ').filter(paint::hasGlyph))
            }.toList()
        }
        val supported = supportedCategories.flatMapTo(HashSet()) { it.emoji }
        val candidateGroups = context.assets.open(VARIANTS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filterNot { it.startsWith('#') || it.isBlank() }.mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                if (parts[0] !in supported) return@mapNotNull null
                val choices = parts[1].split(' ').filter(supported::contains).distinct()
                if (choices.size < 2) null else EmojiVariantChoices(parts[0], choices)
            }.toList()
        }
        val assigned = HashSet<String>()
        val groups = buildList {
            candidateGroups.forEach { group ->
                if (group.base in assigned) return@forEach
                val choices = group.choices.filterNot(assigned::contains)
                if (choices.size < 2) return@forEach
                add(EmojiVariantChoices(group.base, choices))
                assigned.addAll(choices)
            }
        }
        val variants = buildMap {
            groups.forEach { group -> group.choices.forEach { put(it, group) } }
        }
        val hidden = assigned - groups.mapTo(HashSet()) { it.base }
        val categories = supportedCategories.map { category ->
            category.copy(emoji = category.emoji.filterNot(hidden::contains))
        }
        CatalogData(categories, variants)
    }.getOrElse {
        CatalogData(listOf(EmojiCategory("表情", "😀", listOf("😀", "😂", "🥰", "👍", "❤️"))), emptyMap())
    }

    @Synchronized fun recent(context: Context): List<String> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    @Synchronized fun remember(context: Context, emoji: String) {
        val updated = (listOf(emoji) + recent(context).filterNot { it == emoji }).take(MAX_RECENT)
        val array = JSONArray()
        updated.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECENT, array.toString()).apply()
    }
}
