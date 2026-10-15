package com.ersaz.hermesmobile.media

object WhisperBridge {
    init { System.loadLibrary("hermes_whisper") }
    external fun prepare()
    external fun transcribe(model: String, audio: String): String
    external fun cancel()
}
