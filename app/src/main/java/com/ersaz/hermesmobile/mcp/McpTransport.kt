package com.ersaz.hermesmobile.mcp

import com.ersaz.hermesmobile.sandbox.Sandbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The server answered 401: sign-in needed. [challenge] is the raw WWW-Authenticate header, if any. */
class McpAuthRequired(val challenge: String?) : IOException("This MCP server needs you to sign in.")

class McpProtocolException(message: String, val code: Int = 0) : IOException(message)

const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** JSON-RPC session to one MCP server over some wire. */
abstract class McpTransport {
    private val ids = AtomicLong(1)
    protected val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    /** Server-initiated messages (notifications, requests) — handled by the owner. */
    var onServerMessage: ((JSONObject) -> Unit)? = null

    protected abstract suspend fun post(message: JSONObject, expectId: Long?): JSONObject?
    abstract fun close()
    open val diagnostics: String get() = ""

    /** Send a request and return its `result`; throws [McpProtocolException] for a JSON-RPC error. */
    suspend fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 60_000): JSONObject {
        val id = ids.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val waiter = CompletableDeferred<JSONObject>().also { pending[id] = it }
        try {
            val direct = withTimeout(timeoutMs) { post(msg, id) }
            val reply = direct ?: withTimeout(timeoutMs) { waiter.await() }
            reply.optJSONObject("error")?.let { throw McpProtocolException(it.optString("message", "MCP error"), it.optInt("code")) }
            return reply.optJSONObject("result") ?: JSONObject()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw McpProtocolException("Timed out waiting for '$method' from the MCP server.")
        } finally { pending.remove(id) }
    }

    suspend fun notify(method: String, params: JSONObject? = null) {
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) msg.put("params", params)
        post(msg, null)
    }

    /** Reply to a server→client request (e.g. ping, roots/list). */
    protected suspend fun respondTo(request: JSONObject) {
        val id = request.opt("id") ?: return
        val resp = JSONObject().put("jsonrpc", "2.0").put("id", id)
        when (request.optString("method")) {
            "ping" -> resp.put("result", JSONObject())
            "roots/list" -> resp.put("result", JSONObject().put("roots", org.json.JSONArray()))
            else -> resp.put("error", JSONObject().put("code", -32601).put("message", "Method not supported by this client"))
        }
        try { post(resp, null) } catch (_: Exception) {}
    }

    /** Route one inbound JSON-RPC message from any wire. Returns true if it completed a pending request. */
    protected fun dispatch(m: JSONObject, scope: CoroutineScope?): Boolean {
        val id = m.opt("id")
        if (m.has("method")) {
            if (id != null && scope != null) scope.launch { respondTo(m) } else onServerMessage?.invoke(m)
            return false
        }
        val key = (id as? Number)?.toLong() ?: id?.toString()?.toLongOrNull() ?: return false
        pending[key]?.complete(m)
        return true
    }
}

/** Streamable HTTP (2025 spec): each request is a POST answered with JSON or an SSE stream. */
class StreamableHttpTransport(
    private val http: OkHttpClient,
    private val url: String,
    private val headers: () -> Map<String, String>,
) : McpTransport() {
    @Volatile private var sessionId: String? = null
    @Volatile var protocolVersion: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastCall: Call? = null

    override suspend fun post(message: JSONObject, expectId: Long?): JSONObject? = withContext(Dispatchers.IO) {
        val rb = message.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(rb)
            .header("Accept", "application/json, text/event-stream")
            .apply {
                sessionId?.let { header("Mcp-Session-Id", it) }
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                headers().forEach { (k, v) -> header(k, v) }
            }.build()
        val call = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(req).also { lastCall = it }
        call.execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            if (resp.code == 401) throw McpAuthRequired(resp.header("WWW-Authenticate"))
            if (resp.code == 202 || resp.code == 204) return@withContext null
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(300)
                throw McpHttpException(resp.code, "HTTP ${resp.code} from MCP server${if (body.isNotBlank()) ": $body" else ""}")
            }
            if (expectId == null) return@withContext null
            val ctype = resp.header("Content-Type").orEmpty()
            val src = resp.body ?: return@withContext null
            if (ctype.contains("text/event-stream")) {
                readSse(src.charStream().buffered(), expectId)
            } else {
                val text = src.string()
                if (text.isBlank()) return@withContext null
                val t = text.trim()
                if (t.startsWith("[")) {
                    val arr = org.json.JSONArray(t)
                    var found: JSONObject? = null
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                        if ((o.opt("id") as? Number)?.toLong() == expectId && !o.has("method")) found = o else dispatch(o, scope)
                    }
                    found
                } else {
                    val o = JSONObject(t)
                    if ((o.opt("id") as? Number)?.toLong() == expectId && !o.has("method")) o else { dispatch(o, scope); null }
                }
            }
        }
    }

    private fun readSse(reader: BufferedReader, expectId: Long): JSONObject? {
        val data = StringBuilder()
        fun flush(): JSONObject? {
            if (data.isEmpty()) return null
            val payload = data.toString().trimEnd('\n'); data.clear()
            val o = try { JSONObject(payload) } catch (_: Exception) { return null }
            return if ((o.opt("id") as? Number)?.toLong() == expectId && !o.has("method")) o else { dispatch(o, scope); null }
        }
        while (true) {
            val line = reader.readLine() ?: return flush()
            when {
                line.isEmpty() -> flush()?.let { return it }
                line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
            }
        }
    }

    override fun close() {
        lastCall?.cancel()
        val sid = sessionId
        if (sid != null) scope.launch {
            try { http.newCall(Request.Builder().url(url).delete().header("Mcp-Session-Id", sid).apply { headers().forEach { (k, v) -> header(k, v) } }.build()).execute().close() } catch (_: Exception) {}
            scope.cancel()
        } else scope.cancel()
    }
}

class McpHttpException(val status: Int, message: String) : IOException(message)

/** Legacy HTTP+SSE transport: a long-lived GET stream carries replies; requests are POSTed to the announced endpoint. */
class SseTransport(
    private val http: OkHttpClient,
    private val url: String,
    private val headers: () -> Map<String, String>,
) : McpTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoint = CompletableDeferred<String>()
    private var streamCall: Call? = null
    private var reader: Job? = null

    suspend fun open(timeoutMs: Long = 20_000) {
        val req = Request.Builder().url(url).get().header("Accept", "text/event-stream").apply { headers().forEach { (k, v) -> header(k, v) } }.build()
        val call = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(req).also { streamCall = it }
        reader = scope.launch {
            try {
                call.execute().use { resp ->
                    if (resp.code == 401) { endpoint.completeExceptionally(McpAuthRequired(resp.header("WWW-Authenticate"))); return@use }
                    if (!resp.isSuccessful) { endpoint.completeExceptionally(McpHttpException(resp.code, "HTTP ${resp.code} opening the SSE stream")); return@use }
                    val r = resp.body!!.charStream().buffered()
                    var event = "message"; val data = StringBuilder()
                    while (true) {
                        val line = r.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) handleEvent(event, data.toString().trimEnd('\n'))
                                event = "message"; data.clear()
                            }
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                        }
                    }
                }
            } catch (e: Exception) { if (!endpoint.isCompleted) endpoint.completeExceptionally(e) }
            pending.values.forEach { it.completeExceptionally(IOException("MCP SSE stream closed")) }
        }
        withTimeout(timeoutMs) { endpoint.await() }
    }

    private fun handleEvent(event: String, data: String) {
        if (event == "endpoint") {
            val resolved = try { URI(url).resolve(data.trim()).toString() } catch (_: Exception) { data.trim() }
            endpoint.complete(resolved)
        } else if (event == "message") {
            try { dispatch(JSONObject(data), scope) } catch (_: Exception) {}
        }
    }

    override suspend fun post(message: JSONObject, expectId: Long?): JSONObject? = withContext(Dispatchers.IO) {
        val ep = endpoint.await()
        val req = Request.Builder().url(ep).post(message.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers().forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw McpAuthRequired(resp.header("WWW-Authenticate"))
            if (!resp.isSuccessful) throw McpHttpException(resp.code, "HTTP ${resp.code} from MCP endpoint")
        }
        null // the reply arrives on the SSE stream
    }

    override fun close() { streamCall?.cancel(); reader?.cancel(); scope.cancel() }
}

/** Local server speaking newline-delimited JSON-RPC over a process in the Linux sandbox. */
class StdioTransport(
    private val sandbox: Sandbox,
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
) : McpTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proc: Process? = null
    private val stderrTail = StringBuilder()
    override val diagnostics: String get() = synchronized(stderrTail) { stderrTail.toString().takeLast(1500) }

    fun start() {
        val line = (listOf(command) + args).joinToString(" ") { Sandbox.shQuote(it) }
        val pb = sandbox.processBuilder(listOf("/bin/sh", "-c", "cd /root; exec $line"), "/root", env)
        val p = pb.start().also { proc = it }
        scope.launch {
            try {
                p.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        val t = l.trim()
                        if (!t.startsWith("{")) continue // logs sometimes share stdout
                        try { dispatch(JSONObject(t), scope) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            pending.values.forEach { it.completeExceptionally(IOException("MCP server process exited. ${diagnostics.takeLast(300)}")) }
        }
        scope.launch {
            try {
                p.errorStream.bufferedReader().use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        synchronized(stderrTail) { stderrTail.append(l).append('\n'); if (stderrTail.length > 6000) stderrTail.delete(0, 3000) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override suspend fun post(message: JSONObject, expectId: Long?): JSONObject? = withContext(Dispatchers.IO) {
        val p = proc ?: throw IOException("MCP server is not running")
        if (!p.isAlive) throw IOException("MCP server process exited. ${diagnostics.takeLast(300)}")
        synchronized(this@StdioTransport) {
            p.outputStream.write((message.toString() + "\n").toByteArray()); p.outputStream.flush()
        }
        null
    }

    override fun close() { proc?.destroyForcibly(); scope.cancel() }
}
