package com.kingzcheung.xime

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

/** Small decoded previews only; full-resolution images stay on disk. */
internal object ThumbnailCache {
    private val budget = (Runtime.getRuntime().maxMemory() / 32)
        .coerceIn(2L * 1024 * 1024, 8L * 1024 * 1024).toInt()
    private val memory = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val locks = Array(32) { Any() }

    fun get(key: String): Bitmap? = memory.get(key)

    /** Concurrent requests for the same preview share the download and decode. */
    fun load(key: String, source: () -> File?): Bitmap? =
        memory.get(key) ?: synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            memory.get(key) ?: source()?.let(MyGoApi::thumbnail)?.also { memory.put(key, it) }
        }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) memory.evictAll()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) memory.trimToSize(budget / 2)
    }
}
