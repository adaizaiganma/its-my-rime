package com.kingzcheung.xime.util

import android.util.Log

/** Compatibility for Xime's RimeEngine wrapper. */
object FileLogger {
    fun e(tag: String, message: String, error: Throwable) = Log.e(tag, message, error)
    fun w(tag: String, message: String) = Log.w(tag, message)
}
