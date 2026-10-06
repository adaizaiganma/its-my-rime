package com.kingzcheung.xime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MyGoImage(val id: String, val url: String, val alt: String) {
    val mimeType: String get() = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
}

data class MyGoPage(val images: List<MyGoImage>, val hasNext: Boolean)

/** Public v1 API documented by miyago9267/MyGO-Searcher; no repository code is bundled. */
object MyGoApi {
    private const val BASE_URL = "https://mygo.miyago9267.com/api/v1"
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val PAGE_CACHE_MS = 5 * 60 * 1000L
    private const val MAX_DISK_BYTES = 64L * 1024 * 1024
    private const val IMAGE_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private data class CachedPage(val page: MyGoPage, val fetchedAt: Long)
    private val pages = LruCache<String, CachedPage>(12)
    private val imageLocks = Array(32) { Any() }
    private val cleanupLock = Any()
    @Volatile private var lastCleanup = -1L

    fun page(query: String, page: Int): MyGoPage {
        val key = "$page\n$query"
        pages.get(key)?.takeIf { SystemClock.elapsedRealtime() - it.fetchedAt < PAGE_CACHE_MS }
            ?.let { return it.page }
        return fetchPage(query, page).also { pages.put(key, CachedPage(it, SystemClock.elapsedRealtime())) }
    }

    private fun fetchPage(query: String, page: Int): MyGoPage {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val path = if (query.isBlank()) "/images?order=popularity&page=$page&limit=20"
            else "/images/search?q=$encoded&page=$page&limit=20"
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) error("MyGO API HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val data = json.getJSONArray("data")
            val images = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (!url.startsWith("https://storageapi.miyago9267.com/")) continue
                    add(MyGoImage(item.optString("id"), url, item.optString("alt")))
                }
            }
            return MyGoPage(images, json.optJSONObject("meta")?.optBoolean("hasNext") == true)
        } finally {
            connection.disconnect()
        }
    }

    fun cachedImage(context: Context, image: MyGoImage): File =
        synchronized(imageLocks[(image.url.hashCode() and Int.MAX_VALUE) % imageLocks.size]) {
            fetchImage(context, image)
        }

    private fun fetchImage(context: Context, image: MyGoImage): File {
        val directory = File(context.cacheDir, "mygo").apply { mkdirs() }
        val safeId = image.id.filter(Char::isDigit).ifEmpty { image.url.hashCode().toUInt().toString() }
        val extension = image.url.substringBefore('?').substringAfterLast('.', "jpg").lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
        val file = File(directory, "$safeId.$extension")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            trimDiskCache(directory, file)
            return file
        }
        val temp = File.createTempFile("$safeId-", ".part", directory)
        val connection = (URL(image.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode !in 200..299) error("圖片 HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Image download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) error("圖片超過大小限制")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (temp.length() == 0L || (!temp.renameTo(file) && !file.isFile)) error("圖片儲存失敗")
            trimDiskCache(directory, file)
            return file
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun trimDiskCache(directory: File, protected: File) {
        val now = SystemClock.elapsedRealtime()
        if (lastCleanup >= 0 && now - lastCleanup < 60 * 60 * 1000L) return
        synchronized(cleanupLock) {
            if (lastCleanup >= 0 && now - lastCleanup < 60 * 60 * 1000L) return
            val cutoff = System.currentTimeMillis() - IMAGE_CACHE_AGE_MS
            // Read metadata once; sorting must not repeatedly stat every file.
            val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension != "part" }
                .map { Triple(it, it.lastModified(), it.length()) }.sortedByDescending { it.second }
            var retainedBytes = protected.length()
            files.forEach { (file, touched, size) ->
                if (file == protected) return@forEach
                if (touched < cutoff || retainedBytes + size > MAX_DISK_BYTES) file.delete()
                else retainedBytes += size
            }
            lastCleanup = now
        }
    }

    fun thumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 240) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
