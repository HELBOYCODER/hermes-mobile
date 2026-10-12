package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.StreamSink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmClientTest {
    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun streamsTextReasoningAndToolCalls() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(
            """{"choices":[{"delta":{"reasoning_content":"thinking "}}]}""",
            """{"choices":[{"delta":{"content":"Let me "}}]}""",
            """{"choices":[{"delta":{"content":"check."}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"terminal","arguments":"{\"comm"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\":\"ls\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"memory","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}""",
        )))
        server.start()
        val streamed = StringBuilder()
        val client = LlmClient(OkHttpClient(), server.url("/v1").toString(), "sk-test", "openrouter")
        val r = client.chat("hermes", listOf(Message("user", "hi")), sink = object : StreamSink {
            override fun onText(delta: String) { streamed.append(delta) }
        })
        assertEquals("Let me check.", r.content)
        assertEquals("Let me check.", streamed.toString())
        assertEquals("thinking ", r.reasoning)
        assertEquals(2, r.toolCalls.size)
        assertEquals("terminal", r.toolCalls[0].name)
        assertEquals("ls", JSONObject(r.toolCalls[0].arguments).getString("command"))
        assertEquals("call_2", r.toolCalls[1].id)
        assertEquals(120L, r.usage!!.promptTokens)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertEquals(true, JSONObject(req.body.readUtf8()).getBoolean("stream"))
        server.shutdown()
    }

    @Test
    fun nonStreamingAndThinkTags() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"content":"<think>plan it</think>Done."},"finish_reason":"stop"}]}"""
        ))
        server.start()
        val r = LlmClient(OkHttpClient(), server.url("/v1").toString(), "", "custom")
            .chat("m", listOf(Message("user", "x")), stream = false)
        assertEquals("Done.", r.content)
        assertEquals("plan it", r.reasoning)
        server.shutdown()
    }
}
