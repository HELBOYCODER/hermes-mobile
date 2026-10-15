package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.coding.CodingDefaults
import com.ersaz.hermesmobile.mcp.McpEnvironment
import com.ersaz.hermesmobile.mcp.McpServer
import com.ersaz.hermesmobile.mcp.McpStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CodingDefaultsTest {
    @Test fun seedsLocalToolsButDoesNotReconnectOptionalServices() {
        val dir = Files.createTempDirectory("coding-defaults").toFile()
        try {
            val store = McpStore(File(dir, "servers.json"))
            val marker = File(dir, ".seed")
            CodingDefaults.seed(store, marker)
            assertTrue(store.get("hermes-code")!!.enabled)
            assertEquals("python3", store.get("hermes-code")!!.command)
            assertFalse(store.get("context7-docs")!!.enabled)
            assertFalse(store.get("github-readonly")!!.enabled)
            store.remove("hermes-code")
            CodingDefaults.seed(store, marker)
            assertNull(store.get("hermes-code"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun preservesExistingCustomOrDisabledServers() {
        val dir = Files.createTempDirectory("coding-custom").toFile()
        try {
            val store = McpStore(File(dir, "servers.json"))
            store.upsert(McpServer("hermes-code", command = "custom", enabled = false))
            CodingDefaults.seed(store, File(dir, ".seed"))
            assertEquals("custom", store.get("hermes-code")!!.command)
            assertFalse(store.get("hermes-code")!!.enabled)
        } finally { dir.deleteRecursively() }
    }
    @Test fun resolvesHeaderSecretsWithoutChangingStoredTemplate() {
        val template = "Bearer \${GITHUB_TOKEN}"
        assertEquals("Bearer a\$1token", McpEnvironment.resolve(template, mapOf("GITHUB_TOKEN" to "a\$1token")))
        assertEquals("Bearer \${GITHUB_TOKEN}", template)
    }
    @Test fun missingSecretAndHeaderInjectionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { McpEnvironment.resolve("\${TOKEN}", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { McpEnvironment.resolve("\${TOKEN}", mapOf("TOKEN" to "bad\r\nheader")) }
    }
}
