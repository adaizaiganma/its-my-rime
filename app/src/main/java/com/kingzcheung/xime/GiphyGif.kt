package com.kingzcheung.xime

import android.content.Context
import com.giphy.sdk.core.models.Media
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A short-lived local copy gives the receiving editor a readable content URI. */
internal object GiphyGif {
    private const val MAX_GIF_BYTES = 20 * 1024 * 1024
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

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
        val directory = File(context.cacheDir, "giphy_send").apply { mkdirs() }
        directory.listFiles()?.forEach { old ->
            if (old.isFile && System.currentTimeMillis() - old.lastModified() > MAX_AGE_MS) old.delete()
        }
        val file = File.createTempFile("gif-", ".gif", directory)
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
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
