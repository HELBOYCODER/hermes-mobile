package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.files.GeneratedFiles
import com.ersaz.hermesmobile.files.WebLinks
import com.ersaz.hermesmobile.agent.Message
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GeneratedFilesTest {
    @Test fun snapshotsSurviveSourceEditsAndDeduplicateIdenticalFiles() {
        val root = Files.createTempDirectory("deliveries").toFile()
        try {
            val store = GeneratedFiles(root, File(root, "saved"))
            val source = File(root, "گزارش.txt").apply { writeText("first") }
            val saved = store.capture(source)
            assertEquals(saved, store.capture(source))
            source.writeText("second")
            val next = store.capture(source)
            assertNotEquals(saved, next)
            source.delete()
            assertEquals("first", saved.readText())
            assertEquals("second", next.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsMissingFilesAndSymlinksOutsideSandbox() {
        val root = Files.createTempDirectory("sandbox").toFile()
        val outside = File.createTempFile("outside", ".txt")
        try {
            val store = GeneratedFiles(root, File(root, "saved"))
            assertThrows(IllegalArgumentException::class.java) { store.capture(File(root, "missing")) }
            val link = File(root, "link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { store.capture(link) }
        } finally { root.deleteRecursively(); outside.delete() }
    }
    @Test fun attachmentMetadataDoesNotEnterModelRequests() {
        val message = Message("user", "make a report", generatedFiles = listOf("/root/.hermes/deliveries/report.pdf"))
        assertEquals(message.generatedFiles, Message.fromJson(message.toJson()).generatedFiles)
        assertFalse(message.toApi().has("generated_files"))
    }
    @Test fun filenamesAreSafeAndBoundedInUtf8() {
        assertEquals("report.txt", GeneratedFiles.safeName("../../report.txt"))
        assertEquals("download", GeneratedFiles.safeName(".."))
        assertTrue(GeneratedFiles.safeName("گزارش".repeat(80)).toByteArray().size <= 180)
        assertFalse(GeneratedFiles.safeName("bad\nname").contains('\n'))
    }
    @Test fun webLinksAreClickableTargetsWithoutUnsafeSchemes() {
        assertEquals("https://example.com/doc?q=1", WebLinks.webTarget("https://example.com/doc?q=1،"))
        assertEquals("https://example.com", WebLinks.webTarget("<https://example.com>"))
        assertNull(WebLinks.webTarget("javascript:alert(1)"))
        assertNull(WebLinks.webTarget("https:///missing-host"))
        assertEquals("/root/outputs/report.pdf", WebLinks.localTarget("sandbox:/root/outputs/report.pdf"))
        assertNull(WebLinks.localTarget("file:///data/private"))
    }
}
