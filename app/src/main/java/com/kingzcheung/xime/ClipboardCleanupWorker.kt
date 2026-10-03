package com.kingzcheung.xime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Remove expired unpinned clipboard entries and their stored image files. */
class ClipboardCleanupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = runCatching {
        ClipboardHistory.entries(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }
}
