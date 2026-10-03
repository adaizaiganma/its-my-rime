package com.kingzcheung.xime

import android.content.Context
import android.net.Uri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ClipboardEntry(
    val text: String? = null,
    val imageFileName: String? = null,
    val mimeType: String? = null,
    val label: String? = null,
    val pinned: Boolean,
    val copiedAt: Long
) {
    val id: String get() = imageFileName?.let { "image:$it" } ?: "text:$text"
}

/** Text and locally copied images from the system clipboard, retained across process restarts. */
object ClipboardHistory {
    private const val PREFS = "clipboard_history"
    private const val ENTRIES = "entries"
    private const val CLEARED_AT = "cleared_at"
    private const val MAX_RECENT = 30
    private const val MAX_PINNED = 20
    private const val MAX_TEXT_LENGTH = 100_000
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val IMAGE_DIRECTORY = "clipboard_images"
    private const val CLEANUP_WORK = "clipboard_history_cleanup"
    private const val RECENT_LIFETIME_MS = 24L * 60 * 60 * 1000

    @Synchronized fun entries(context: Context): List<ClipboardEntry> = read(context)
        .sortedByDescending { it.copiedAt }

    @Synchronized fun startCleanup(context: Context) {
        scheduleCleanup(context, read(context))
    }

    @Synchronized fun clearRecent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(CLEARED_AT, System.currentTimeMillis()).apply()
        save(context, read(context).filter { it.pinned })
    }

    fun captureTime(context: Context, timestamp: Long, fromChange: Boolean): Long? {
        val now = System.currentTimeMillis()
        val copiedAt = if (timestamp > 0) timestamp.coerceAtMost(now)
            else if (fromChange) now else return null
        if (copiedAt <= now - RECENT_LIFETIME_MS) return null
        return copiedAt.takeIf { it > lastClearedAt(context) }
    }

    @Synchronized fun record(context: Context, text: String, copiedAt: Long = System.currentTimeMillis()): Boolean {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH || copiedAt <= lastClearedAt(context)) return false
        val existing = read(context)
        val updated = ClipboardEntry(text = text, pinned = existing.firstOrNull { it.text == text }?.pinned ?: false,
            copiedAt = copiedAt)
        save(context, listOf(updated) + existing.filterNot { it.id == updated.id })
        return true
    }

    @Synchronized fun recordImageFile(context: Context, source: File, mimeType: String, label: String): ClipboardEntry? =
        source.inputStream().use { saveImage(context, it, mimeType, label, System.currentTimeMillis(), false) }

    @Synchronized fun recordImageUri(context: Context, uri: Uri, mimeType: String, label: String,
                                     copiedAt: Long = System.currentTimeMillis()): ClipboardEntry? =
        context.contentResolver.openInputStream(uri)?.use { saveImage(context, it, mimeType, label, copiedAt, true) }

    @Synchronized fun togglePinned(context: Context, id: String): Boolean? {
        val existing = read(context)
        val entry = existing.firstOrNull { it.id == id } ?: return null
        val pinned = !entry.pinned
        save(context, existing.map { if (it.id == id) it.copy(pinned = pinned) else it })
        return pinned
    }

    fun imageFile(context: Context, entry: ClipboardEntry): File? {
        val name = entry.imageFileName ?: return null
        if (!Regex("[a-f0-9]{64}\\.(jpg|png|webp|gif)").matches(name)) return null
        return File(File(context.filesDir, IMAGE_DIRECTORY), name).takeIf { it.isFile }
    }

    private fun saveImage(context: Context, input: InputStream, mimeType: String, label: String,
                          copiedAt: Long, respectClearAt: Boolean): ClipboardEntry? {
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> return null
        }
        val directory = File(context.filesDir, IMAGE_DIRECTORY).apply { mkdirs() }
        val temp = File.createTempFile("image-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0
            temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_IMAGE_BYTES) return null
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (size == 0) return null
            if (respectClearAt && copiedAt <= lastClearedAt(context)) return null
            val fileName = digest.digest().joinToString("") { "%02x".format(it) } + ".$extension"
            val target = File(directory, fileName)
            if (!target.isFile && !temp.renameTo(target)) return null
            val existing = read(context)
            val updated = ClipboardEntry(imageFileName = fileName, mimeType = mimeType, label = label,
                pinned = existing.firstOrNull { it.imageFileName == fileName }?.pinned ?: false,
                copiedAt = copiedAt)
            save(context, listOf(updated) + existing.filterNot { it.id == updated.id })
            return updated
        } finally {
            temp.delete()
        }
    }

    private fun read(context: Context): List<ClipboardEntry> {
        val stored = load(context)
        val active = stored.filter { it.pinned || it.copiedAt > System.currentTimeMillis() - RECENT_LIFETIME_MS }
        if (active.size != stored.size) save(context, active)
        return active
    }

    private fun load(context: Context): List<ClipboardEntry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val image = item.optString("imageFileName").takeIf(String::isNotEmpty)
                val text = item.optString("text").takeIf(String::isNotEmpty)
                if (image != null || text != null) add(ClipboardEntry(
                    text = text,
                    imageFileName = image,
                    mimeType = item.optString("mimeType").takeIf(String::isNotEmpty),
                    label = item.optString("label").takeIf(String::isNotEmpty),
                    pinned = item.optBoolean("pinned"),
                    copiedAt = item.optLong("copiedAt")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, entries: List<ClipboardEntry>) {
        val cutoff = System.currentTimeMillis() - RECENT_LIFETIME_MS
        val recent = entries.filterNot { it.pinned }.filter { it.copiedAt > cutoff }
            .sortedByDescending { it.copiedAt }.take(MAX_RECENT)
        val pinned = entries.filter { it.pinned }.sortedByDescending { it.copiedAt }.take(MAX_PINNED)
        val saved = pinned + recent
        val array = JSONArray()
        saved.forEach { entry ->
            array.put(JSONObject().apply {
                entry.text?.let { put("text", it) }
                entry.imageFileName?.let { put("imageFileName", it) }
                entry.mimeType?.let { put("mimeType", it) }
                entry.label?.let { put("label", it) }
                put("pinned", entry.pinned)
                put("copiedAt", entry.copiedAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENTRIES, array.toString()).apply()
        val retained = saved.mapNotNull { it.imageFileName }.toSet()
        File(context.filesDir, IMAGE_DIRECTORY).listFiles()?.forEach { file ->
            if (file.isFile && file.name !in retained) file.delete()
        }
        scheduleCleanup(context, saved)
    }

    private fun scheduleCleanup(context: Context, entries: List<ClipboardEntry>) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (entries.none { !it.pinned }) {
            manager.cancelUniqueWork(CLEANUP_WORK)
        } else {
            val request = PeriodicWorkRequestBuilder<ClipboardCleanupWorker>(1, TimeUnit.HOURS).build()
            manager.enqueueUniquePeriodicWork(CLEANUP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private fun lastClearedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLEARED_AT, 0)
}
