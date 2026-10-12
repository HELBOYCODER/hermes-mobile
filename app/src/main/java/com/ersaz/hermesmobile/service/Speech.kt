package com.ersaz.hermesmobile.service

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/** Phone TTS engine as Hermes' text_to_speech backend. */
class Speech(private val context: Context, private val dir: File) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    }

    suspend fun synthesize(text: String, language: String = ""): File? {
        ensure()
        if (withTimeoutOrNull(5000) { ready.await() } != true) return null
        val engine = tts ?: return null
        dir.mkdirs()
        if (language.isNotBlank()) engine.language = Locale.forLanguageTag(language)
        val id = UUID.randomUUID().toString().take(8)
        val out = File(dir, "tts_$id.wav")
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
        })
        engine.synthesizeToFile(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), null, out, id)
        val ok = withTimeoutOrNull(60_000) { done.await() } == true
        return if (ok && out.exists()) out else null
    }

    fun play(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start() }
        } catch (_: Exception) {}
    }

    fun speak(text: String) {
        ensure()
        tts?.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "speak")
    }

    fun stop() { tts?.stop(); player?.stop() }
}
