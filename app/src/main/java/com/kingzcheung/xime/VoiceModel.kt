package com.kingzcheung.xime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Downloads and tracks the on-device SenseVoice model used for voice input. */
internal object VoiceModel {
    sealed interface State {
        data object Missing : State
        data class Downloading(val downloaded: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private class ModelFile(val name: String, val url: String, val size: Long, val sha256: String?)

    private const val DIR = "voice/sense-voice-zh-en-int8-2025-09-09"
    private const val SENSE_VOICE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main"
    private val files = listOf(
        ModelFile("model.int8.onnx", "$SENSE_VOICE/model.int8.onnx", 237_115_547,
            "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"),
        // Small text file served over HTTPS; its size is checked instead of a hash.
        ModelFile("tokens.txt", "$SENSE_VOICE/tokens.txt", 315_894, null),
        ModelFile("silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )
    val totalBytes: Long = files.sumOf { it.size }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<(State) -> Unit>()
    @Volatile private var cancelled = false
    @Volatile private var downloading = false
    @Volatile var state: State = State.Missing
        private set

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR)
    fun modelPath(context: Context): String = File(dir(context), "model.int8.onnx").absolutePath
    fun tokensPath(context: Context): String = File(dir(context), "tokens.txt").absolutePath
    fun vadPath(context: Context): String = File(dir(context), "silero_vad.onnx").absolutePath

    /** Files are only renamed into place after verification, so presence and size mean a finished download. */
    fun isReady(context: Context): Boolean = files.all { File(dir(context), it.name).length() == it.size }

    fun refresh(context: Context) {
        if (!downloading) publish(if (isReady(context)) State.Ready else State.Missing)
    }

    fun observe(observer: (State) -> Unit) {
        observers.add(observer)
        observer(state)
    }

    fun removeObserver(observer: (State) -> Unit) {
        observers.remove(observer)
    }

    fun download(context: Context) {
        if (downloading) return
        val app = context.applicationContext
        downloading = true
        cancelled = false
        Thread({
            val result = runCatching { downloadAll(app) }
            downloading = false
            publish(result.fold(
                onSuccess = { if (isReady(app)) State.Ready else State.Missing },
                onFailure = { error ->
                    if (cancelled) State.Missing else State.Failed(error.message ?: "下載失敗，請重試")
                }
            ))
        }, "voice-model-download").apply { isDaemon = true }.start()
    }

    fun cancel() {
        cancelled = true
    }

    fun delete(context: Context) {
        cancel()
        VoiceEngine.release()
        dir(context).deleteRecursively()
        if (!downloading) publish(State.Missing)
    }

    private fun downloadAll(context: Context) {
        val dir = dir(context).apply { mkdirs() }
        var completed = files.filter { File(dir, it.name).length() == it.size }.sumOf { it.size }
        publish(State.Downloading(completed, totalBytes))
        for (file in files) {
            val target = File(dir, file.name)
            if (target.length() == file.size) continue
            val part = File(dir, file.name + ".part")
            fetch(file, part) { written -> publish(State.Downloading(completed + written, totalBytes)) }
            verify(file, part)
            if (!part.renameTo(target)) throw IOException("無法儲存 ${file.name}")
            completed += file.size
        }
    }

    // Resumes from an existing .part file so a dropped connection doesn't restart a 237 MB download.
    private fun fetch(file: ModelFile, part: File, progress: (Long) -> Unit) {
        if (part.length() > file.size) part.delete()
        val resumeFrom = part.length()
        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = connection.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (code != HttpURLConnection.HTTP_OK && !append) throw IOException("伺服器回應 $code")
            var written = if (append) resumeFrom else 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw IOException("已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            progress(written)
                        }
                    }
                }
            }
            progress(written)
        } finally {
            connection.disconnect()
        }
    }

    private fun verify(file: ModelFile, part: File) {
        if (part.length() != file.size) {
            part.delete()
            throw IOException("${file.name} 下載不完整，請重試")
        }
        val expected = file.sha256 ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            part.delete()
            throw IOException("${file.name} 校驗失敗，請重新下載")
        }
    }

    private fun publish(next: State) {
        state = next
        main.post { observers.forEach { it(next) } }
    }
}
