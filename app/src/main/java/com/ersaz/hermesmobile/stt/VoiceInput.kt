package com.ersaz.hermesmobile.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Languages offered by the mic button. `code` is the tag Google's speech service expects. */
enum class VoiceLang(val code: String, val short: String, val label: String) {
    FA("fa-IR", "فا", "فارسی"),
    EN("en-US", "EN", "English"),
    AUTO("auto", "AUTO", "Auto-detect");

    fun next(): VoiceLang = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromCode(code: String): VoiceLang = entries.firstOrNull { it.code == code } ?: FA
    }
}

/**
 * Live dictation through Google's online speech engine (the same full-duplex engine the Sokhan
 * keyboard uses). Audio is captured at 16 kHz mono, streamed as FLAC, and interim + final text
 * is pushed to [onText] as it arrives so the chat box fills in while the user is speaking.
 */
class VoiceInput(
    private val onText: (StreamText) -> Unit,
    private val onDone: (final: String, error: String?) -> Unit,
    /** Called with true once recording stopped and the engine is flushing the last words. */
    private val onProcessing: (Boolean) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false

    val active: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start(lang: VoiceLang) {
        if (active) return
        stopRequested = false
        cancelled = false
        job = scope.launch {
            val session = SdaSession(lang.code, scope)
            var record: AudioRecord? = null
            var captions: Job? = null
            val live = java.util.concurrent.atomic.AtomicReference(StreamText("", ""))
            try {
                val minBuf = AudioRecord.getMinBufferSize(FLAC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, FLAC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, FLAC_SAMPLE_RATE * 2 * 8),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microphone unavailable")
                record.startRecording()
                session.start()
                captions = launch { session.liveText.collect { live.set(it); onText(it) } }

                val buffer = ShortArray(1_600) // 100 ms
                val maxSamples = 10 * 60 * FLAC_SAMPLE_RATE
                var total = 0
                while (!stopRequested && !cancelled && total < maxSamples) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) throw IllegalStateException("audio read error $n")
                    if (n == 0) { delay(10); continue }
                    val floats = FloatArray(n) { buffer[it] / 32768f }
                    session.feed(floats)
                    total += n
                }
                record.stop(); record.release(); record = null

                if (cancelled) {
                    session.cancel()
                    captions.cancel()
                    onDone("", null)
                    return@launch
                }
                onProcessing(true)
                // The server can take a long time (or never answer) to close the stream; never leave the UI
                // stuck: wait briefly for the final transcript, then fall back to what was already recognised.
                val result = withTimeoutOrNull(FINISH_WAIT_MS) { session.finish() }
                if (result == null) session.cancel()
                captions.cancel()
                val (text, err) = resolveText(result, live.get())
                onDone(finalize(text, lang), err)
            } catch (e: Exception) {
                runCatching { record?.release() }
                session.cancel()
                captions?.cancel()
                onDone("", e.message ?: "recording failed")
            }
        }
    }

    /** Stop capturing and let the server flush the last words. */
    fun stop() { stopRequested = true }

    /** Abort without producing text. */
    fun cancel() { cancelled = true; stopRequested = true }

    companion object {
        /** How long to wait for the final transcript after the user stops recording. */
        const val FINISH_WAIT_MS = 6_000L

        /**
         * Pick the text for a finished dictation: the engine's final transcript when it arrived, otherwise the
         * live (committed + tentative) text, otherwise an error that explains why nothing came back.
         */
        fun resolveText(result: Result<String>?, live: StreamText): Pair<String, String?> {
            val final = result?.getOrNull().orEmpty().trim()
            if (final.isNotEmpty()) return final to null
            val liveText = (live.committed + " " + live.tentative).trim()
            if (liveText.isNotEmpty()) return liveText to null
            val err = result?.exceptionOrNull()?.message
            return "" to (err ?: "No speech detected — try again closer to the mic.")
        }

        /** Same post-processing the Sokhan keyboard applies to dictated text. */
        fun finalize(raw: String, lang: VoiceLang): String {
            val formatted = TextFormat.format(raw)
            return if (lang == VoiceLang.EN) formatted else applyPersianHalfspace(formatted)
        }

        /** Live preview text (committed + tentative) shown while the user is still speaking. */
        fun preview(t: StreamText, lang: VoiceLang): String =
            finalize((t.committed + " " + t.tentative).trim(), lang)
    }
}
