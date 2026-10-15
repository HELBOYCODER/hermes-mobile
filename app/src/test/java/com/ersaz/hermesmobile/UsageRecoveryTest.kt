package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.UsageStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UsageRecoveryTest {
    @Test fun malformedRowsDoNotCrashStatsAndValidRowsSurvive() {
        val dir = Files.createTempDirectory("usage-recovery").toFile()
        try {
            val file = File(dir, "usage.json")
            file.writeText("""{"2099-01-01":{"valid":{"calls":1,"prompt":50,"completion":10},"bad":null},"2099-01-02":"broken"}""")
            val rows = UsageStore(file).rows(7)
            assertEquals(1, rows.size)
            assertEquals("valid", rows.single().model)
        } finally { dir.deleteRecursively() }
    }
}
