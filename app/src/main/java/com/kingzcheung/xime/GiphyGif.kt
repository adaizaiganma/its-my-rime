package com.kingzcheung.xime

import android.content.Context
import android.os.SystemClock
import com.giphy.sdk.core.models.Media
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A short-lived local copy gives the receiving editor a readable content URI. */
internal object GiphyGif {
    private const val MAX_GIF_BYTES = 20 * 1024 * 1024
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private val locks = Array(16) { Any() }
    private val cleanupLock = Any()
    @Volatile private var lastCleanup = -1L

    fun prepareForInsertion(context: Context, media: Media): File {
        val url = listOf(
            media.images.downsizedLarge?.gifUrl,
            media.images.downsizedMedium?.gifUrl,
            media.images.downsized?.gifUrl,
            media.images.original?.gifUrl
        ).firstOrNull { !it.isNullOrBlank() } ?: error("GIF URL unavailable")
        val address = URL(url)
        require(address.protocol == "https" &&
            (address.host == "giphy.com" || address.host.endsWith(".giphy.com"))) {
            "Unexpected GIF host"
        }
        return synchronized(locks[(url.hashCode() and Int.MAX_VALUE) % locks.size]) {
            cachedDownload(context, address, url)
        }
    }

    private fun cachedDownload(context: Context, address: URL, url: String): File {
        val directory = File(context.cacheDir, "giphy_send").apply { mkdirs() }
        cleanExpired(directory)
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$name.gif")
        if (target.isFile && target.length() > 0 &&
            System.currentTimeMillis() - target.lastModified() < MAX_AGE_MS) return target
        val file = File.createTempFile("gif-", ".part", directory)
        val connection = (address.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) error("GIF HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("GIF download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_GIF_BYTES) error("GIF is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (file.length() == 0L) error("Empty GIF")
            file.inputStream().use { input ->
                val signature = ByteArray(3)
                require(input.read(signature) == 3 && signature.contentEquals("GIF".toByteArray())) {
                    "Invalid GIF"
                }
            }
            check(file.renameTo(target)) { "GIF cache write failed" }
            return target
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            connection.disconnect()
            file.delete()
        }
    }

    private fun cleanExpired(directory: File) {
        val now = SystemClock.elapsedRealtime()
        if (lastCleanup >= 0 && now - lastCleanup < 60 * 60 * 1000L) return
        synchronized(cleanupLock) {
            if (lastCleanup >= 0 && now - lastCleanup < 60 * 60 * 1000L) return
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            directory.listFiles()?.forEach { old ->
                if (old.isFile && old.lastModified() < cutoff) old.delete()
            }
            lastCleanup = now
        }
    }
}
