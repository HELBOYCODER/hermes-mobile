package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.SessionStore
import com.ersaz.hermesmobile.gateway.GatewayManager
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.LlmException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Regression tests for the 2.6.3 stability fixes. */
class StabilityFixesTest {

    // ---- gateway allow-list: display names must never authorize

    @Test
    fun allowListMatchesIdOrHandleButNotDisplayName() {
        val allowed = listOf("12345", "@Erfan_Dev")
        assertTrue(GatewayManager.isAllowed(allowed, "12345", null))
        assertTrue(GatewayManager.isAllowed(allowed, "999", "erfan_dev")) // Telegram usernames are case-insensitive
        assertTrue(GatewayManager.isAllowed(allowed, "999", "@Erfan_Dev"))
        // A Telegram user without a username used to be matched by first_name, which anyone can set.
        assertFalse(GatewayManager.isAllowed(allowed, "999", null))
        assertFalse(GatewayManager.isAllowed(allowed, "999", ""))
        assertFalse(GatewayManager.isAllowed(allowed, "", "someone"))
        assertTrue(GatewayManager.isAllowed(listOf("*"), "anything", null))
        assertFalse(GatewayManager.isAllowed(emptyList(), "12345", "erfan_dev"))
    }

    // ---- LLM client: transient network failures are retried, HTTP errors keep their semantics

    @Test
    fun droppedConnectionBeforeResponseIsRetried() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"content":"recovered"},"finish_reason":"stop"}]}"""
        ))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        // OkHttp's own silent retry is off, so only the client's retry can recover the turn.
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val r = LlmClient(http, "http://127.0.0.1:${server.port}/v1", "", "custom")
            .chat("m", listOf(Message("user", "x")), stream = false)
        assertEquals("recovered", r.content)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad model"}}"""))
        server.start()
        try {
            LlmClient(OkHttpClient(), server.url("/v1").toString(), "", "custom")
                .chat("m", listOf(Message("user", "x")), stream = false)
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertEquals(400, e.status)
            assertTrue(e.message!!.contains("bad model"))
        }
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    // ---- session store: saving while the agent appends must not crash

    @Test
    fun savingAndReadingWhileMessagesAreAppendedDoesNotThrow() {
        val dir = Files.createTempDirectory("sessions").toFile()
        val store = SessionStore(dir)
        val session = Session(source = "app", messages = List(200) { Message("user", "seed $it") })
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val writer = thread {
            var i = 0
            while (running.get() && session.messages.size < 3000) {
                session.messages += Message("assistant", "delta ${i++}")
                if (i % 50 == 0) session.messages.removeAt(0)
            }
        }
        try {
            repeat(100) {
                store.save(session)
                // What ChatViewModel.rebuild() does on the main thread while the agent runs.
                session.messages.forEach { m -> m.content.length }
            }
        } finally {
            running.set(false)
            writer.join(TimeUnit.SECONDS.toMillis(5))
        }
        val saved = JSONObject(File(dir, "${session.id}.json").readText())
        assertEquals(session.id, saved.getString("id"))
        assertTrue(store.list().any { it.id == session.id })
        dir.deleteRecursively()
    }
}
