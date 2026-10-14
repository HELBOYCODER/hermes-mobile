package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.agent.Session
import org.junit.Assert.*
import org.junit.Test

class FailedTurnTest {
    @Test fun failureIsLocalMetadataAndSurvivesReload() {
        val message = Message("user", "سلام", images = listOf("data:image/test"), failureReason = "HTTP 503")
        val loaded = Message.fromJson(message.toJson())
        assertEquals(message.localId, loaded.localId)
        assertEquals("HTTP 503", loaded.failureReason)
        assertFalse(loaded.toApi().has("failure_reason"))
        assertFalse(loaded.toApi().has("local_id"))
    }
    @Test fun retryKeepsPriorHistoryAndOriginalAttachments() {
        val previous = Message("user", "earlier")
        val failed = Message("user", "سلام", images = listOf("image"), failureReason = "HTTP 403")
        val session = Session(messages = mutableListOf(previous, Message("assistant", "answer"), failed, Message("tool", "completed tool output")))
        val retry = session.prepareFailedTurnForRetry(failed.localId)!!
        assertEquals("سلام", retry.content)
        assertEquals(listOf("image"), retry.images)
        assertNull(retry.failureReason)
        assertEquals(4, session.messages.size)
        assertEquals("completed tool output", session.messages.last().content)
        assertNull(session.prepareFailedTurnForRetry(failed.localId))
    }
    @Test fun cannotDiscardLaterUserTurnsOrRetrySuccessfulTurn() {
        val failed = Message("user", "failed", failureReason = "offline")
        val later = Message("user", "later")
        val session = Session(messages = mutableListOf(failed, later))
        assertNull(session.prepareFailedTurnForRetry(failed.localId))
        assertNull(session.prepareFailedTurnForRetry(later.localId))
        assertEquals(2, session.messages.size)
    }
}
