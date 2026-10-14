package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.core.EnvironmentVariables as Env
import org.junit.Assert.*
import org.junit.Test

class EnvironmentVariablesTest {
    @Test fun preservesLiteralValues() {
        val value = "  \"token=a=b\" $ literal  "
        assertEquals(value, Env.parse(Env.serialize(mapOf("API_KEY" to value)))["API_KEY"])
    }
    @Test fun rejectsInvalidNamesAndHandlesEmptyValues() {
        assertEquals(mapOf("EMPTY" to "", "GOOD" to "last"), Env.parse("EMPTY=\n1BAD=x\nGOOD=first\nGOOD=last\n# comment"))
    }
    @Test fun rejectsNewlineInjection() {
        assertThrows(IllegalArgumentException::class.java) { Env.serialize(mapOf("KEY" to "x\nOTHER=y")) }
    }
    @Test fun redactsOverlappingValues() {
        assertEquals("[REDACTED] [REDACTED]", Env.redact("secret-long secret", mapOf("A_KEY" to "secret", "B_TOKEN" to "secret-long", "C" to "")))
    }
}
