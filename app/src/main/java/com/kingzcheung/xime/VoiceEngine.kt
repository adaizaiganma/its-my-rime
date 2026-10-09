package com.kingzcheung.xime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt

/** On-device speech recognition: microphone → Silero VAD → SenseVoice, one sentence per pause. */
internal object VoiceEngine {
    sealed interface Event {
        data object Loading : Event
        data class Listening(val level: Float) : Event
        data object Recognizing : Event
        data class Text(val text: String) : Event
        data class Error(val message: String) : Event
        data object Stopped : Event
    }

    private const val SAMPLE_RATE = 16_000
    private const val CHUNK = 512

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var session: Thread? = null
    @Volatile private var running = false

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val isRunning: Boolean get() = running

    /**
     * Starts listening; [onEvent] is called on the main thread until [Event.Stopped].
     * [transform] runs on the recognition thread, so slow work such as script conversion stays off the UI.
     */
    fun start(context: Context, transform: (String) -> String = { it }, onEvent: (Event) -> Unit) {
        if (running) return
        val app = context.applicationContext
        running = true
        session = Thread({ listen(app, transform) { event -> main.post { onEvent(event) } } }, "voice-input").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops recording; speech captured so far is still recognized before [Event.Stopped]. */
    fun stop() {
        running = false
    }

    /** Frees the ~250 MB model; the next session reloads it. */
    fun release() {
        if (running) return
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasMicPermission before AudioRecord is created.
    private fun listen(context: Context, transform: (String) -> String, emit: (Event) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record: AudioRecord? = null
        var vad: Vad? = null
        try {
            if (!VoiceModel.isReady(context)) return emit(Event.Error("尚未下載語音模型"))
            if (!hasMicPermission(context)) return emit(Event.Error("需要麥克風權限"))
            val engine = synchronized(lock) {
                recognizer ?: run {
                    emit(Event.Loading)
                    OfflineRecognizer(null, recognizerConfig(context)).also { recognizer = it }
                }
            }
            vad = Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VoiceModel.vadPath(context),
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = CHUNK,
                    maxSpeechDuration = 20f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ))
            // Two seconds of buffer absorbs recording while a sentence is being decoded.
            val bufferBytes = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SAMPLE_RATE * 2 * 2
            )
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (record.state != AudioRecord.STATE_INITIALIZED) return emit(Event.Error("無法開啟麥克風"))
            record.startRecording()
            emit(Event.Listening(0f))
            val pcm = ShortArray(CHUNK)
            var lastLevel = 0L
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { pcm[it] / 32768f }
                vad.acceptWaveform(samples)
                val now = System.currentTimeMillis()
                if (now - lastLevel > 80) {
                    lastLevel = now
                    emit(Event.Listening(rms(samples)))
                }
                decodeSegments(engine, vad, transform, emit)
            }
            record.stop()
            vad.flush()
            decodeSegments(engine, vad, transform, emit)
        } catch (error: Throwable) {
            emit(Event.Error(error.message ?: "語音辨識失敗"))
        } finally {
            record?.release()
            vad?.release()
            running = false
            session = null
            emit(Event.Stopped)
        }
    }

    private fun decodeSegments(engine: OfflineRecognizer, vad: Vad, transform: (String) -> String,
                               emit: (Event) -> Unit) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            emit(Event.Recognizing)
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                engine.decode(stream)
                val text = engine.getResult(stream).text.trim()
                if (text.isNotEmpty()) emit(Event.Text(transform(text)))
            } finally {
                stream.release()
            }
            if (running) emit(Event.Listening(0f))
        }
    }

    private fun recognizerConfig(context: Context) = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = VoiceModel.modelPath(context),
                language = "zh",
                // Inverse text normalisation adds punctuation and writes numbers as digits.
                useInverseTextNormalization = true,
            ),
            tokens = VoiceModel.tokensPath(context),
            numThreads = 2,
            debug = false,
            provider = "cpu",
        ),
    )

    private fun rms(samples: FloatArray): Float {
        var sum = 0f
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size)
    }
}
