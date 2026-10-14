package com.ersaz.hermesmobile.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Voice input via the phone's speech recognizer. Must be driven from the main thread. */
class Dictation(private val context: Context) {
    private var rec: SpeechRecognizer? = null
    var listening = false
        private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onPartial] gets live text, [onFinal] the final transcript (blank on no match), [onError] a readable reason. */
    fun start(language: String, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context).also { rec = it }
        listening = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
            override fun onResults(b: Bundle?) { listening = false; onFinal(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()); cleanup() }
            override fun onError(code: Int) {
                listening = false
                onError(when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                    else -> "Speech recognition error ($code)."
                }); cleanup()
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifBlank { Locale.getDefault().toLanguageTag() }))
    }

    /** Finish and deliver what was heard so far. */
    fun finish() { rec?.stopListening() }

    fun stop() { listening = false; rec?.cancel(); cleanup() }

    private fun cleanup() { rec?.destroy(); rec = null }
}
