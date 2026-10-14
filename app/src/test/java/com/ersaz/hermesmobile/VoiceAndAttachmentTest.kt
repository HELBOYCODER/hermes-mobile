package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.stt.StreamText
import com.ersaz.hermesmobile.stt.VoiceInput
import com.ersaz.hermesmobile.stt.VoiceLang
import com.ersaz.hermesmobile.ui.Attachment
import com.ersaz.hermesmobile.ui.attachmentNote
import com.ersaz.hermesmobile.ui.formatSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndAttachmentTest {
    @Test
    fun languageCycleCoversFarsiEnglishAuto() {
        assertEquals(VoiceLang.EN, VoiceLang.FA.next())
        assertEquals(VoiceLang.AUTO, VoiceLang.EN.next())
        assertEquals(VoiceLang.FA, VoiceLang.AUTO.next())
        assertEquals("fa-IR", VoiceLang.FA.code)
        assertEquals(VoiceLang.FA, VoiceLang.fromCode("garbage"))
    }

    @Test
    fun livePreviewJoinsCommittedAndTentative() {
        val t = StreamText("hello comma", "world")
        assertEquals("Hello, world", VoiceInput.preview(t, VoiceLang.EN))
    }

    @Test
    fun persianGetsHalfSpace() {
        val out = VoiceInput.finalize("می رود", VoiceLang.FA)
        assertTrue(out.contains('\u200C'))
    }

    @Test
    fun attachmentNoteListsEveryFile() {
        assertEquals("", attachmentNote(emptyList()))
        val note = attachmentNote(listOf(Attachment("a.pdf", "/root/uploads/a.pdf", "application/pdf", 2048)))
        assertTrue(note.contains("/root/uploads/a.pdf"))
        assertTrue(note.contains("2.0 KB"))
        assertEquals("512 B", formatSize(512))
    }
}
