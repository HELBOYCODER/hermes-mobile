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
import kotlinx.coroutines.launch
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
                captions = launch { session.liveText.collect { onText(it) } }

                val buffer = ShortArray(1_600) // 100 ms
                val maxSamples = 10 * 60 * FLAC_SAMPLE_RATE
                var total = 0
                while (!stopRequested && !cancelled && total < maxSamples) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) throw IllegalStateException("audio read error $n")
                    if (n == 0) continue
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
                val result = session.finish()
                captions.cancel()
                val text = result.getOrNull().orEmpty()
                val err = result.exceptionOrNull()?.message
                onDone(finalize(text, lang), if (text.isBlank()) err else null)
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
