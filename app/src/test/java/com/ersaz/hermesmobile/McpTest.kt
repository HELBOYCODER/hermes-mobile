package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.mcp.McpAuthRequired
import com.ersaz.hermesmobile.mcp.McpDiscovery
import com.ersaz.hermesmobile.mcp.McpInput
import com.ersaz.hermesmobile.mcp.McpManager
import com.ersaz.hermesmobile.mcp.McpServer
import com.ersaz.hermesmobile.mcp.McpStore
import com.ersaz.hermesmobile.mcp.McpToolDef
import com.ersaz.hermesmobile.mcp.SseTransport
import com.ersaz.hermesmobile.mcp.StreamableHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class McpTest {

    // ---------------------------------------------------------------- input parsing

    private fun parse(s: String) = McpInput.parse(s) { it }

    @Test
    fun urlBecomesARemoteServerNamedAfterTheHost() {
        val p = parse("https://mcp.notion.com/mcp")
        val s = p.servers.single()
        assertEquals("notion", s.id); assertEquals("https://mcp.notion.com/mcp", s.url); assertEquals("auto", s.transport)
        assertEquals("sse", parse("https://example.com/sse").servers.single().transport)
        assertEquals("githubcopilot", parse("https://api.githubcopilot.com/mcp/").servers.single().id)
    }

    @Test
    fun commandLinesBecomeStdioServers() {
        val s = parse("npx -y @modelcontextprotocol/server-everything").servers.single()
        assertEquals("npx", s.command); assertEquals(listOf("-y", "@modelcontextprotocol/server-everything"), s.args)
        assertEquals("everything", s.id)
        val q = parse("""uvx mcp-server-fetch --user-agent "My Agent 1.0"""").servers.single()
        assertEquals(listOf("mcp-server-fetch", "--user-agent", "My Agent 1.0"), q.args)
        assertEquals("fetch", q.id)
        assertEquals("npx", parse("claude mcp add foo -- npx -y bar").servers.single().command)
    }

    @Test
    fun claudeDesktopJsonIsAccepted() {
        val p = parse("""{"mcpServers":{"gh":{"url":"https://x.test/mcp","headers":{"Authorization":"Bearer t"}},
            "fs":{"command":"npx","args":["-y","fs"],"env":{"A":"1"},"disabled":true}}}""")
        assertEquals(listOf("gh", "fs"), p.servers.map { it.id })
        assertEquals("Bearer t", p.servers[0].headers["Authorization"])
        assertEquals(false, p.servers[1].enabled); assertEquals("1", p.servers[1].env["A"])
        assertEquals("single", parse("""{"name":"single","url":"https://a.b/mcp"}""").servers.single().id)
        assertTrue(parse("{}").error != null)
        assertTrue(parse("not json {").servers.isNotEmpty()) // treated as a (weird) command, not a crash
        assertTrue(parse("   ").error != null)
    }

    @Test
    fun storeRoundTripsAndCreatesUniqueIds() {
        val f = Files.createTempDirectory("mcp").resolve("servers.json").toFile()
        val st = McpStore(f)
        st.upsert(McpServer("a", url = "https://a/mcp", headers = mapOf("K" to "V"), note = "n", transport = "sse", oauthClientId = "cid"))
        st.upsert(McpServer("b", command = "npx", args = listOf("-y", "x"), env = mapOf("E" to "1"), enabled = false))
        val back = McpStore(f).all()
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals("sse", back[0].transport); assertEquals("cid", back[0].oauthClientId); assertEquals("V", back[0].headers["K"])
        assertEquals(false, back[1].enabled); assertEquals(listOf("-y", "x"), back[1].args)
        assertEquals("a-2", st.uniqueId("a")); assertEquals("fresh", st.uniqueId("Fresh!"))
        assertTrue(st.remove("a")); assertNull(st.get("a"))
        assertTrue(JSONObject(f.readText()).has("mcpServers"))
    }

    @Test
    fun toolNamesAreValidUniqueAndShort() {
        val used = mutableSetOf<String>()
        val a = McpManager.wireName(McpToolDef("my server", "search docs!", "", JSONObject()), used)
        assertEquals("mcp__my_server__search_docs_", a)
        val long = McpManager.wireName(McpToolDef("s", "x".repeat(100), "", JSONObject()), used)
        assertTrue(long.length <= 64 && Regex("[A-Za-z0-9_-]+").matches(long))
        val dup = McpManager.wireName(McpToolDef("my server", "search docs?", "", JSONObject()), used)
        assertTrue(dup != a && used.size == 3)
        val schema = McpManager.normalizeSchema(JSONObject("""{"${'$'}schema":"x","type":"object"}"""))
        assertTrue(!schema.has("\$schema") && schema.has("properties"))
        assertEquals("object", McpManager.normalizeSchema(null).getString("type"))
    }

    // ---------------------------------------------------------------- Streamable HTTP

    private fun rpcResult(id: Any?, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    @Test
    fun streamableHttpHandlesJsonSseAndSessionHeaders() = runBlocking {
        val seenSessions = mutableListOf<String?>()
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val body = JSONObject(r.body.readUtf8())
                    seenSessions += r.getHeader("Mcp-Session-Id")
                    val id = body.opt("id")
                    return when (body.optString("method")) {
                        "initialize" -> MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "sess-1")
                            .setBody(rpcResult(id, """{"protocolVersion":"2025-06-18","serverInfo":{"name":"mock"}}"""))
                        "notifications/initialized" -> MockResponse().setResponseCode(202)
                        "tools/list" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                                "event: message\ndata: " + rpcResult(id, """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") + "\n\n")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        val t = StreamableHttpTransport(OkHttpClient(), srv.url("/mcp").toString()) { mapOf("X-Test" to "1") }
        val init = t.request("initialize", JSONObject())
        assertEquals("mock", init.getJSONObject("serverInfo").getString("name"))
        t.notify("notifications/initialized")
        val tools = t.request("tools/list")
        assertEquals("echo", tools.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals(listOf(null, "sess-1", "sess-1"), seenSessions)
        assertEquals("1", srv.takeRequest().getHeader("X-Test"))
        srv.shutdown()
    }

    @Test
    fun a401SurfacesTheChallenge() = runBlocking {
        val srv = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", """Bearer resource_metadata="https://x/.well-known/oauth-protected-resource""""))
            start()
        }
        val t = StreamableHttpTransport(OkHttpClient(), srv.url("/mcp").toString()) { emptyMap() }
        try { t.request("initialize"); fail() } catch (e: McpAuthRequired) { assertTrue(e.challenge!!.contains("resource_metadata")) }
        srv.shutdown()
    }

    // ---------------------------------------------------------------- legacy SSE

    @Test
    fun legacySseDeliversRepliesOnTheStream() = runBlocking {
        val repliesQueue = java.util.concurrent.LinkedBlockingQueue<String>()
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    if (r.method == "GET") {
                        // The stream: endpoint event, then whatever POSTs queue up.
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("event: endpoint\ndata: /messages?s=1\n\n")
                            .setBodyDelay(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .also { repliesQueue.clear() }
                    }
                    return MockResponse().setResponseCode(202)
                }
            }
            start()
        }
        val t = SseTransport(OkHttpClient(), srv.url("/sse").toString()) { emptyMap() }
        t.open() // resolves the endpoint announced on the stream
        srv.shutdown(); t.close()
    }

    // ---------------------------------------------------------------- OAuth discovery

    @Test
    fun discoveryFollowsProtectedResourceMetadataToTheAuthServer() = runBlocking {
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val base = srv.url("").toString().trimEnd('/')
                return when (r.path) {
                    "/.well-known/oauth-protected-resource" -> json("""{"resource":"$base/mcp","authorization_servers":["$base"],"scopes_supported":["read","write"]}""")
                    "/.well-known/oauth-authorization-server" -> json("""{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        srv.start()
        val meta = McpDiscovery(OkHttpClient()).discover(srv.url("/mcp").toString(), """Bearer realm="x", scope="read"""")
        val base = srv.url("").toString().trimEnd('/')
        assertEquals("$base/authorize", meta.authorizationEndpoint)
        assertEquals("$base/token", meta.tokenEndpoint)
        assertEquals("$base/register", meta.registrationEndpoint)
        assertEquals(listOf("read"), meta.scopes) // the challenge's scope wins
        srv.shutdown()
    }

    @Test
    fun discoveryFallsBackToDefaultEndpointsWithoutMetadata() = runBlocking {
        val srv = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(404) }; start() }
        val meta = McpDiscovery(OkHttpClient()).discover(srv.url("/mcp").toString(), null)
        val base = srv.url("").toString().trimEnd('/')
        assertEquals("$base/authorize", meta.authorizationEndpoint); assertEquals("$base/register", meta.registrationEndpoint)
        srv.shutdown()
    }

    private fun json(b: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(b)
}
