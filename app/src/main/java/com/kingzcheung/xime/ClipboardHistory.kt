package com.kingzcheung.xime

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Executor

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
    private val imageNamePattern = Regex("[a-f0-9]{64}\\.(jpg|png|webp|gif)")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "clipboard-history").apply { isDaemon = true }
    }
    private val imageWorker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "clipboard-images").apply { isDaemon = true }
    }
    private var cachedRaw: String? = null
    private var cachedEntries: List<ClipboardEntry> = emptyList()
    private var cleanupScheduled: Boolean? = null

    /** History reads and writes are serialized without blocking UI or image I/O. */
    fun <T> runAsync(action: () -> T, callback: ((Result<T>) -> Unit)? = null) =
        executeAsync(worker, action, callback)

    fun <T> runImageAsync(action: () -> T, callback: ((Result<T>) -> Unit)? = null) =
        executeAsync(imageWorker, action, callback)

    private fun <T> executeAsync(executor: Executor, action: () -> T, callback: ((Result<T>) -> Unit)?) {
        executor.execute {
            val result = runCatching(action)
            result.exceptionOrNull()?.let { Log.w("ClipboardHistory", "Clipboard operation failed", it) }
            callback?.let { main.post { it(result) } }
        }
    }

    @Synchronized fun entries(context: Context): List<ClipboardEntry> = read(context)
        .sortedByDescending { it.copiedAt }

    fun startCleanup(context: Context) {
        val app = context.applicationContext
        runAsync({ synchronized(this) { scheduleCleanup(app, read(app)) } })
    }

    @Synchronized fun clearRecent(context: Context, clearedAt: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(CLEARED_AT, clearedAt).apply()
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

    fun recordImageFile(context: Context, source: File, mimeType: String, label: String): ClipboardEntry? =
        source.inputStream().use { saveImage(context, it, mimeType, label, System.currentTimeMillis(), false) }

    fun recordImageUri(context: Context, uri: Uri, mimeType: String, label: String,
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
        if (!imageNamePattern.matches(name)) return null
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
            val fileName = digest.digest().joinToString("") { "%02x".format(it) } + ".$extension"
            return synchronized(this) {
                if (respectClearAt && copiedAt <= lastClearedAt(context)) return@synchronized null
                // Expiration can delete orphan files. Read first, then atomically
                // publish the image and its metadata under the same short lock.
                val existing = read(context)
                val target = File(directory, fileName)
                if (!target.isFile && !temp.renameTo(target)) return@synchronized null
                val updated = ClipboardEntry(imageFileName = fileName, mimeType = mimeType, label = label,
                    pinned = existing.firstOrNull { it.imageFileName == fileName }?.pinned ?: false,
                    copiedAt = copiedAt)
                save(context, listOf(updated) + existing.filterNot { it.id == updated.id })
                updated
            }
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

    private fun load(context: Context): List<ClipboardEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, "[]") ?: "[]"
        if (raw == cachedRaw) return cachedEntries
        val loaded = runCatching {
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
        cachedRaw = raw
        cachedEntries = loaded
        return loaded
    }

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
        val raw = array.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENTRIES, raw).apply()
        cachedRaw = raw
        cachedEntries = saved
        val retained = saved.mapNotNull { it.imageFileName }.toSet()
        val abandonedBefore = System.currentTimeMillis() - RECENT_LIFETIME_MS
        File(context.filesDir, IMAGE_DIRECTORY).listFiles()?.forEach { file ->
            if (file.isFile && file.name !in retained &&
                (file.extension != "part" || file.lastModified() < abandonedBefore)) file.delete()
        }
        scheduleCleanup(context, saved)
    }

    private fun scheduleCleanup(context: Context, entries: List<ClipboardEntry>) {
        val needed = entries.any { !it.pinned }
        if (cleanupScheduled == needed) return
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!needed) {
            manager.cancelUniqueWork(CLEANUP_WORK)
        } else {
            val request = PeriodicWorkRequestBuilder<ClipboardCleanupWorker>(1, TimeUnit.HOURS).build()
            manager.enqueueUniquePeriodicWork(CLEANUP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        cleanupScheduled = needed
    }

    private fun lastClearedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLEARED_AT, 0)
}
