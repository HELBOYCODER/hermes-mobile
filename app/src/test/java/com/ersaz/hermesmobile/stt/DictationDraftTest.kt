package com.ersaz.hermesmobile.stt

import org.junit.Assert.*
import org.junit.Test

class DictationDraftTest {
    @Test fun pauseResumeKeepsEarlierWordsAndIgnoresOldCallbacks() {
        val draft = DictationDraft()
        val first = draft.begin("typed")
        val pausedText = draft.update(first, "first words")!!
        draft.pause()
        assertNull(draft.update(first, "late final"))
        val resumed = draft.begin(pausedText)
        assertNull(draft.update(first, "overwrites new recording"))
        assertEquals("typed first words next words", draft.update(resumed, "next words"))
        assertEquals("typed first words next final", draft.update(resumed, "next final"))
    }
    @Test fun pausedDraftCanBeSentAndNewMessageRemainsIndependent() {
        val draft = DictationDraft()
        val first = draft.begin("")
        assertEquals("send this", draft.update(first, "send this"))
        draft.pause()
        val next = draft.begin("")
        assertEquals("new message", draft.update(next, "new message"))
        assertNull(draft.update(first, "late text after send"))
    }
    @Test fun silentResumePreservesExistingDraft() {
        val draft = DictationDraft()
        val token = draft.begin("متن قبلی")
        assertEquals("متن قبلی", draft.update(token, ""))
    }
}
