package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.ToolLoopDetector
import com.ersaz.hermesmobile.agent.UsageStore
import com.ersaz.hermesmobile.backup.Zips
import com.ersaz.hermesmobile.core.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ParityTest {
    @Test
    fun loopDetectorWarnsThenStopsOnlyOnIdenticalRepeats() {
        val d = ToolLoopDetector(warnAt = 3, stopAt = 5)
        assertEquals(ToolLoopDetector.Verdict.OK, d.record("terminal", "{\"command\":\"ls\"}", "a"))
        assertEquals(ToolLoopDetector.Verdict.OK, d.record("terminal", "{\"command\":\"ls\"}", "a"))
        assertEquals(ToolLoopDetector.Verdict.WARN, d.record("terminal", "{\"command\":\"ls\"}", "a"))
        assertTrue(d.nudge("terminal").contains("3 times"))
        assertEquals(ToolLoopDetector.Verdict.WARN, d.record("terminal", "{\"command\":\"ls\"}", "a"))
        assertEquals(ToolLoopDetector.Verdict.STOP, d.record("terminal", "{\"command\":\"ls\"}", "a"))
        // A different result (progress) resets the streak.
        assertEquals(ToolLoopDetector.Verdict.OK, d.record("terminal", "{\"command\":\"ls\"}", "b"))
        assertEquals(1, d.count())
    }

    @Test
    fun usageStoreAggregatesPerDayAndModel() {
        val f = Files.createTempDirectory("u").resolve("usage.json").toFile()
        val u = UsageStore(f)
        val day = 1_800_000_000_000L
        u.record("m1", 100, 10, day); u.record("m1", 50, 5, day); u.record("m2", 7, 3, day + 1000)
        val rows = u.rows(7, day + 86_400_000L)
        val m1 = rows.first { it.model == "m1" }
        assertEquals(2, m1.calls); assertEquals(150, m1.prompt); assertEquals(15, m1.completion)
        assertEquals(2, rows.size)
        assertTrue("old data falls out of the window", u.rows(1, day + 40 * 86_400_000L).isEmpty())
    }

    @Test
    fun zipRoundTripSkipsExcludedFilesAndBlocksZipSlip() {
        val src = Files.createTempDirectory("src").toFile()
        java.io.File(src, "sessions").mkdirs()
        java.io.File(src, "sessions/a.json").writeText("A")
        java.io.File(src, "audio").mkdirs(); java.io.File(src, "audio/x.wav").writeText("W")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            assertEquals(1, Zips.zipDir(src, "hermes", z) { it.startsWith("audio/") })
            // A hostile entry trying to escape the target directory:
            z.putNextEntry(ZipEntry("hermes/../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dst = Files.createTempDirectory("dst").toFile()
        val n = Zips.unzipPrefix(ZipInputStream(ByteArrayInputStream(bos.toByteArray())), "hermes", dst)
        assertEquals(1, n)
        assertEquals("A", java.io.File(dst, "sessions/a.json").readText())
        assertFalse(java.io.File(dst.parentFile, "evil.txt").exists())
        assertFalse(java.io.File(dst, "audio/x.wav").exists())
    }

    @Test
    fun appLogKeepsOnlyTheNewestLines() {
        val log = AppLog(capacity = 3)
        (1..5).forEach { log.i("t", "m$it") }
        assertEquals(3, log.lines().size)
        assertTrue(log.lines().first().endsWith("m3") && log.lines().last().endsWith("m5"))
        log.e("t", "boom"); assertTrue(log.lines().last().contains(" E/t boom"))
        log.clear(); assertTrue(log.lines().isEmpty())
    }
}
