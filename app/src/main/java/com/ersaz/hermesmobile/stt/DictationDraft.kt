package com.ersaz.hermesmobile.stt

/** Separates a retained draft from each network session's lifetime. Main-thread confined. */
class DictationDraft {
    private var generation = 0
    private var base = ""
    fun begin(existingText: String): Int {
        base = existingText
        return ++generation
    }
    fun accepts(token: Int) = token == generation
    fun pause() { generation++ }
    fun update(token: Int, transcript: String): String? {
        if (!accepts(token)) return null
        return when {
            transcript.isBlank() -> base
            base.isBlank() -> transcript
            else -> base.trimEnd() + " " + transcript
        }
    }
}
