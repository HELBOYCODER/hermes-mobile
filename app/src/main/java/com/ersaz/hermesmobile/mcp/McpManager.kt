package com.ersaz.hermesmobile.mcp

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

sealed class McpStatus {
    data object Disabled : McpStatus()
    data class Connecting(val step: String = "Connecting…") : McpStatus()
    data class Connected(val tools: Int, val serverName: String) : McpStatus()
    data class NeedsAuth(val message: String) : McpStatus()
    data class Failed(val message: String) : McpStatus()
}

data class McpToolDef(val server: String, val name: String, val description: String, val schema: JSONObject)

private class McpConnection(
    val server: McpServer,
    val transport: McpTransport,
    val bearer: AtomicReference<String?>,
    val serverName: String,
    var tools: List<McpToolDef>,
)

/**
 * Owns every MCP server: add (URL / command / JSON), connect, auto sign-in when a server answers 401,
 * and expose the discovered tools to the agent.
 */
class McpManager(private val rt: HermesRuntime) {
    val store = McpStore(File(rt.hermesHome, "mcp/servers.json"))
    val oauth = McpOAuth(rt.app, rt.http, McpAuthStore(rt.app))

    private val conns = ConcurrentHashMap<String, McpConnection>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status
    private val _tick = MutableStateFlow(0)
    /** Bumps whenever servers or tool lists change (UI refresh). */
    val tick: StateFlow<Int> = _tick

    fun servers(): List<McpServer> = store.all()
    fun statusOf(id: String): McpStatus? = _status.value[id]

    /** Hub defaults, seeded once (never resurrecting a user deletion). */
    init { seedHubDefaults(); McpCatalog15.seed(store, rt.hermesHome) }

    private fun seedHubDefaults() {
        val marker = File(rt.hermesHome, "mcp/.hub-defaults-v1")
        val knownBuiltin = setOf("hermes-code", "context7-docs", "github-readonly")
        var changed = false
        // Mark the pre-existing bundled defaults as built-in (preserving
        // the user's enabled state); do not touch user servers.
        store.all().forEach { s ->
            if (s.id in knownBuiltin && !s.builtin) { store.upsert(s.copy(builtin = true)); changed = true }
        }
        if (!marker.exists()) {
            if (store.get("framelink-figma") == null) {
                // Framelink (GLips/Figma-Context-MCP, MIT): figma-developer-mcp
                // via sandbox Node. Disabled until the user's own Figma key
                // is entered (see the Hub's needs-key state); autoStart stays
                // true per the bundled-server rule, but disabled entries are
                // never auto-connected, so a keyless Framelink stays quiet.
                store.upsert(McpServer(
                    id = "framelink-figma", displayName = "Framelink Figma",
                    command = "npx", args = listOf("-y", "figma-developer-mcp"),
                    env = mapOf("FIGMA_API_KEY" to "\${FIGMA_API_KEY}"),
                    enabled = false, builtin = true, autoStart = true,
                    note = "Figma layout/style via figma-developer-mcp. Needs your own Figma API key: set FIGMA_API_KEY in Settings → Environment, or type the key in this server's env below. Paste a Figma link in chat and the agent fetches it with get_figma_data.",
                ))
                changed = true
            }
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        }
        // Xerj local search (xerj-org/xerj, Apache-2.0): `xerj mcp`
        // as a default Hub entry, ON by default. Seeded under its own
        // marker so a user deletion is never resurrected. Until the
        // musl binary is installed (XerjSetup, default ON at startup)
        // this entry honestly reports its connect error.
        val xerjMarker = File(rt.hermesHome, "mcp/.xerj-v1")
        if (!xerjMarker.exists() && store.get("xerj") == null) {
            store.upsert(McpServer(
                id = "xerj", displayName = "Xerj local search",
                command = "xerj", args = listOf("mcp"),
                enabled = true, builtin = true, autoStart = true,
                note = "Local search over /root/projects (BM25 + hybrid, file:line snippets, no external service). Needs the xerj binary: sandbox setup installs the verified v1.0.0-rc.100 musl aarch64 tarball (checksum-verified); until then this entry shows its real error and search falls back to the BM25-ranked SearchFilesTool.",
            ))
            xerjMarker.parentFile?.mkdirs()
            xerjMarker.writeText("1")
            changed = true
        }
        if (changed) _tick.value++
    }

    /** Framelink needs-key check: no usable Figma key anywhere. */
    fun framelinkNeedsKey(): Boolean {
        val s = store.get("framelink-figma") ?: return false
        val literal = s.env["FIGMA_API_KEY"].orEmpty()
        if (literal.isNotBlank() && !literal.contains("\${")) return false
        return rt.settings.envVars()["FIGMA_API_KEY"].isNullOrBlank()
    }

    private fun setStatus(id: String, s: McpStatus) {
        _status.value = _status.value + (id to s); _tick.value++
        when (s) {
            is McpStatus.Failed -> {
                rt.log.e("mcp", "$id: ${s.message}")
                persistLastError(id, s.message)
            }
            is McpStatus.NeedsAuth -> {
                rt.log.i("mcp", "$id needs sign-in: ${s.message}")
                persistLastError(id, s.message)
            }
            is McpStatus.Connected -> {
                rt.log.i("mcp", "$id connected (${s.tools} tools)")
                persistLastError(id, "")
            }
            else -> {}
        }
    }

    /** Hub registry health text: persists only when it actually changes. */
    private fun persistLastError(id: String, err: String) {
        val s = store.get(id) ?: return
        if (s.lastError != err) store.upsert(s.copy(lastError = err))
    }

    // ---------------------------------------------------------------- adding

    class AddResult(val ids: List<String>, val error: String? = null)

    /**
     * Add whatever the user pasted, then connect it right away — signing in automatically when the server asks.
     */
    fun addFromInput(raw: String): AddResult {
        val parsed = McpInput.parse(raw) { store.uniqueId(it) }
        if (parsed.error != null) return AddResult(emptyList(), parsed.error)
        // Names inside one pasted JSON must also be unique among themselves.
        val used = mutableSetOf<String>()
        val saved = parsed.servers.map { s ->
            var id = s.id; var n = 2
            while (id in used) { id = "${s.id}-$n"; n++ }
            used += id
            val fixed = if (id == s.id) s else s.copy(id = id)
            store.upsert(fixed); fixed.id
        }
        _tick.value++
        saved.forEach { id -> rt.scope.launch(Dispatchers.IO) { connect(id, interactive = true) } }
        return AddResult(saved)
    }

    fun update(s: McpServer) { store.upsert(s); _tick.value++ }

    fun setEnabled(id: String, on: Boolean) {
        val s = store.get(id) ?: return
        store.upsert(s.copy(enabled = on))
        if (on) rt.scope.launch(Dispatchers.IO) { connect(id, interactive = false) } else { closeConn(id); setStatus(id, McpStatus.Disabled) }
    }

    fun remove(id: String) {
        closeConn(id); store.remove(id); oauth.store.remove(id)
        _status.value = _status.value - id; _tick.value++
    }

    private fun closeConn(id: String) { conns.remove(id)?.transport?.close() }

    // ---------------------------------------------------------------- connecting

    fun connectAllAsync() {
        servers().forEach { s ->
            if (!s.enabled) setStatus(s.id, McpStatus.Disabled)
            // Hub rule: custom entries stay unconnected until tested
            // (autoStart=false); bundled entries auto-start. A skipped
            // entry keeps no status, so the Hub shows "Not started".
            else if (!s.autoStart) { /* not started yet — test it in the Hub */ }
            else rt.scope.launch(Dispatchers.IO) { connect(s.id, interactive = false) }
        }
    }

    fun reconnectAsync(id: String, interactive: Boolean = true) { rt.scope.launch(Dispatchers.IO) { connect(id, interactive) } }

    /** /reload-mcp: disconnect every connection, then reconnect each
     *  enabled server sequentially and return per-server results.
     *  Disabled servers are reported as disabled, not reconnected. */
    suspend fun reloadAll(): List<Pair<McpServer, McpStatus>> {
        val ids = conns.keys.toList()
        ids.forEach { closeConn(it) }
        return servers().map { s ->
            if (!s.enabled) s to McpStatus.Disabled
            else s to connect(s.id, interactive = false)
        }
    }

    fun signInAsync(id: String) { rt.scope.launch(Dispatchers.IO) { oauth.store.clearTokens(id); connect(id, interactive = true) } }

    fun signOut(id: String) {
        oauth.signOut(id)
        if (store.get(id)?.url != null) { closeConn(id); setStatus(id, McpStatus.NeedsAuth("Signed out.")) }
    }

    suspend fun connect(id: String, interactive: Boolean): McpStatus {
        val lock = locks.getOrPut(id) { Mutex() }
        return lock.withLock { connectLocked(id, interactive) }
    }

    private suspend fun connectLocked(id: String, interactive: Boolean): McpStatus {
        val server = store.get(id) ?: return McpStatus.Failed("Unknown server")
        if (!server.enabled) return McpStatus.Disabled.also { setStatus(id, it) }
        closeConn(id)
        setStatus(id, McpStatus.Connecting())
        var signedInOnce = false
        while (true) {
            try {
                val c = open(server)
                conns[id] = c
                val st = McpStatus.Connected(c.tools.size, c.serverName)
                setStatus(id, st)
                return st
            } catch (e: McpAuthRequired) {
                if (!interactive || signedInOnce || server.url == null) {
                    return McpStatus.NeedsAuth(if (oauth.store.hasToken(id)) "Session expired — sign in again." else "Sign in required.").also { setStatus(id, it) }
                }
                signedInOnce = true
                setStatus(id, McpStatus.Connecting("Signing in…"))
                try {
                    val meta = oauth.discover(server.url!!, e.challenge)
                    oauth.authorize(server, meta) { url -> rt.scope.launch(Dispatchers.Main) { runCatching { rt.app.openInBrowser(url) } } }
                    rt.notifyIfBackground("Signed in to ${server.id}", "Return to Hermes to finish connecting.")
                } catch (ex: CancellationException) { throw ex
                } catch (ex: Exception) {
                    return McpStatus.NeedsAuth(ex.message ?: "Sign-in failed").also { setStatus(id, it) }
                }
                setStatus(id, McpStatus.Connecting("Connecting…"))
                // loop: reconnect with the new token
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                return McpStatus.Failed(friendly(e)).also { setStatus(id, it) }
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is McpHttpException -> e.message ?: "HTTP error"
        is java.net.UnknownHostException -> "Can't resolve the server address."
        is java.net.ConnectException -> "Can't connect to the server."
        else -> e.message ?: e.toString()
    }

    private suspend fun open(server: McpServer): McpConnection {
        val bearer = AtomicReference<String?>(null)
        if (server.url != null) bearer.set(oauth.accessToken(server))
        val headers = { buildMap<String, String> { val variables = rt.settings.envVars().toMutableMap()
            if (!variables.containsKey("GITHUB_TOKEN") && rt.settings.githubToken.isNotBlank()) variables["GITHUB_TOKEN"] = rt.settings.githubToken
            server.headers.forEach { (name, value) -> put(name, McpEnvironment.resolve(value, variables)) }; bearer.get()?.let { put("Authorization", "Bearer $it") } } }
        if (server.isStdio) return handshake(server, startStdio(server), bearer)
        val url = server.url!!
        suspend fun viaSse(): McpConnection = handshake(server, SseTransport(rt.http, url, headers).also { it.open() }, bearer)
        suspend fun viaHttp(): McpConnection = handshake(server, StreamableHttpTransport(rt.http, url, headers), bearer)
        return when (server.transport) {
            "sse" -> viaSse()
            "http" -> viaHttp()
            // Auto: Streamable HTTP first; servers that only speak legacy SSE answer the POST with 400/404/405.
            else -> try { viaHttp() } catch (e: McpHttpException) {
                if (e.status in setOf(400, 404, 405, 406)) viaSse() else throw e
            }
        }
    }

    private suspend fun handshake(server: McpServer, transport: McpTransport, bearer: AtomicReference<String?>): McpConnection {
        try {
            val init = transport.request("initialize", JSONObject()
                .put("protocolVersion", MCP_PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "hermes-agent-android").put("version", com.ersaz.hermesmobile.HermesRuntime.VERSION)),
                timeoutMs = timeoutMs(server, if (server.isStdio) 90_000 else 30_000))
            (transport as? StreamableHttpTransport)?.protocolVersion = init.optString("protocolVersion", MCP_PROTOCOL_VERSION)
            transport.notify("notifications/initialized")
            val name = init.optJSONObject("serverInfo")?.optString("name").orEmpty().ifBlank { server.id }
            val conn = McpConnection(server, transport, bearer, name, emptyList())
            transport.onServerMessage = { m -> if (m.optString("method") == "notifications/tools/list_changed") rt.scope.launch(Dispatchers.IO) { refreshTools(server.id) } }
            conn.tools = listTools(server.id, transport)
            return conn
        } catch (e: Exception) {
            val extra = transport.diagnostics.takeIf { it.isNotBlank() }
            transport.close()
            if (extra != null && e !is McpAuthRequired) throw McpProtocolException("${e.message}\n${extra.takeLast(400)}")
            throw e
        }
    }

    private suspend fun startStdio(server: McpServer): McpTransport {
        val sb = rt.sandbox
        if (!sb.isInstalled() && !sb.ensureInstalled()) throw McpProtocolException("The Linux sandbox isn't installed yet, so local (stdio) MCP servers can't run.")
        ensureRuntime(server)
        // Bounded guest environment (see Sandbox.prootArgv: env -i + the
        // base set + the user's Environment vars + this server's env).
        // ${PLACEHOLDER} values resolve from Environment; a missing one
        // fails the connect with a clear message instead of silently
        // passing a literal "${...}" to the server.
        val variables = rt.settings.envVars().toMutableMap()
        if (variables["GITHUB_TOKEN"].isNullOrBlank() && rt.settings.githubToken.isNotBlank()) variables["GITHUB_TOKEN"] = rt.settings.githubToken
        val resolvedEnv = server.env.mapValues { (k, v) ->
            try { McpEnvironment.resolve(v, variables) }
            catch (e: IllegalArgumentException) { throw McpProtocolException("Server '${server.id}': ${e.message} (env $k)") }
        }
        val cwd = server.cwd.ifBlank { "/root" }.takeIf { it.startsWith("/") } ?: "/root"
        return StdioTransport(sb, server.command!!, server.args, resolvedEnv, cwd).also { it.start() }
    }

    /** Installs node / uv / python on demand so `npx …` and `uvx …` servers just work. */
    private suspend fun ensureRuntime(server: McpServer) {
        val bin = server.command!!.substringAfterLast('/')
        val (check, install) = when (bin) {
            "npx", "npm", "node" -> "command -v $bin" to "apk add --no-cache nodejs npm"
            "uvx", "uv" -> "command -v $bin" to "apk add --no-cache uv || (apk add --no-cache python3 py3-pip && pip install --break-system-packages uv)"
            "python", "python3", "pip", "pip3" -> "command -v $bin" to "apk add --no-cache python3 py3-pip"
            else -> return
        }
        if (rt.sandbox.exec(check, timeoutSec = 20).exitCode == 0) return
        setStatus(server.id, McpStatus.Connecting("Installing $bin in the sandbox (first run only)…"))
        val r = rt.sandbox.exec(install, timeoutSec = 600)
        if (r.exitCode != 0) throw McpProtocolException("Couldn't install '$bin': ${r.output.takeLast(300)}")
    }

    private suspend fun listTools(serverId: String, t: McpTransport): List<McpToolDef> {
        val out = mutableListOf<McpToolDef>()
        var cursor: String? = null
        repeat(20) {
            val res = t.request("tools/list", if (cursor != null) JSONObject().put("cursor", cursor) else JSONObject())
            res.optJSONArray("tools")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                    val name = o.optString("name").ifBlank { return@let }
                    out += McpToolDef(serverId, name, o.optString("description").ifBlank { o.optString("title") }, normalizeSchema(o.optJSONObject("inputSchema")))
                }
            }
            cursor = res.optString("nextCursor").ifBlank { null }
            if (cursor == null) return out
        }
        return out
    }

    private suspend fun refreshTools(id: String) {
        val c = conns[id] ?: return
        try { c.tools = listTools(id, c.transport); setStatus(id, McpStatus.Connected(c.tools.size, c.serverName)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- using tools

    /** Hub per-tool gating, applied on the fresh store entry (not the
     *  connect-time snapshot, so Hub toggles take effect immediately):
     *  denied tools are dropped; a non-empty allowlist keeps only its
     *  members. This is the gating point ToolRegistry reads. */
    private fun visibleDefs(): List<McpToolDef> =
        conns.values.sortedBy { it.server.createdAt }.flatMap { c ->
            val entry = store.get(c.server.id) ?: c.server
            c.tools.filter { d ->
                d.name !in entry.deniedTools && (entry.allowedTools.isEmpty() || d.name in entry.allowedTools)
            }
        }

    fun toolDenied(serverId: String, tool: String): Boolean {
        val entry = store.get(serverId) ?: return false
        return tool in entry.deniedTools || (entry.allowedTools.isNotEmpty() && tool !in entry.allowedTools)
    }

    /** Native per-tool entries (capped) plus the generic `mcp` tool when anything is connected. */
    fun tools(): List<Tool> {
        val defs = visibleDefs()
        if (defs.isEmpty()) return emptyList()
        val used = mutableSetOf<String>()
        val native = defs.take(MAX_NATIVE_TOOLS).map { d -> McpTool(this, d, wireName(d, used)) }
        return native + McpGenericTool(this)
    }

    fun allDefs(): List<McpToolDef> = visibleDefs()

    /** Unfiltered defs for one server (the Hub detail screen's tool list). */
    fun defsFor(serverId: String): List<McpToolDef> = conns[serverId]?.tools.orEmpty()

    /** Last stdio/log output for a server (stderr tail, or "" for remote). */
    fun diagnostics(id: String): String = conns[id]?.transport?.diagnostics.orEmpty()

    private fun timeoutMs(server: McpServer, defaultMs: Long): Long =
        if (server.timeoutSec > 0) server.timeoutSec * 1000L else defaultMs
    fun connectedIds(): List<String> = conns.keys.toList()

    suspend fun awaitReady(maxMs: Long = 10_000) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && _status.value.values.any { it is McpStatus.Connecting && it.step.startsWith("Connecting") }) delay(150)
    }

    suspend fun callTool(serverId: String, tool: String, args: JSONObject, listener: ((String) -> Unit)? = null): String {
        // Hub gating also enforced here, so the generic `mcp` tool cannot
        // bypass a per-tool toggle by calling a denied tool directly.
        if (toolDenied(serverId, tool)) return "Error: tool '$tool' on server '$serverId' is disabled in the MCP Hub."
        var attempt = 0
        while (true) {
            val c = conns[serverId] ?: run {
                val st = statusOf(serverId)
                return when (st) {
                    is McpStatus.NeedsAuth -> "Error: MCP server '$serverId' needs sign-in (open MCP servers in the app and tap Sign in)."
                    else -> "Error: MCP server '$serverId' is not connected${(st as? McpStatus.Failed)?.let { ": ${it.message}" } ?: ""}."
                }
            }
            try {
                if (c.server.url != null) c.bearer.set(oauth.accessToken(c.server))
                val res = c.transport.request("tools/call", JSONObject().put("name", tool).put("arguments", args), timeoutMs = timeoutMs(store.get(serverId) ?: c.server, 300_000))
                return formatResult(res)
            } catch (e: McpAuthRequired) {
                if (attempt++ == 0 && oauth.accessToken(c.server, force = true) != null) { continue }
                setStatus(serverId, McpStatus.NeedsAuth("Session expired — sign in again."))
                return "Error: MCP server '$serverId' needs sign-in again."
            } catch (e: McpHttpException) {
                if (attempt++ == 0 && e.status == 404) { connect(serverId, interactive = false); continue } // session expired
                return "Error: ${e.message}"
            } catch (e: McpProtocolException) {
                return "Error: ${e.message}"
            } catch (e: java.io.IOException) {
                if (attempt++ == 0) { val st = connect(serverId, interactive = false); if (st is McpStatus.Connected) continue }
                return "Error: ${e.message}"
            }
        }
    }

    private fun formatResult(res: JSONObject): String {
        val sb = StringBuilder()
        val content = res.optJSONArray("content")
        if (content != null) for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "image", "audio" -> sb.append(saveBlob(c.optString("data"), c.optString("mimeType"), c.optString("type"))).append('\n')
                "resource" -> c.optJSONObject("resource")?.let { r ->
                    if (r.has("text")) sb.append("[${r.optString("uri")}]\n").append(r.optString("text")).append('\n')
                    else if (r.has("blob")) sb.append(saveBlob(r.optString("blob"), r.optString("mimeType"), "resource")).append('\n')
                }
                "resource_link" -> sb.append("[link] ${c.optString("name")}: ${c.optString("uri")}\n")
                else -> sb.append(c.toString()).append('\n')
            }
        }
        if (sb.isBlank()) res.optJSONObject("structuredContent")?.let { sb.append(it.toString(2)) }
        val text = sb.toString().trim().ifEmpty { "(empty result)" }
        return if (res.optBoolean("isError", false)) "Error from tool: $text" else text
    }

    private fun saveBlob(b64: String, mime: String, kind: String): String = try {
        val ext = mime.substringAfter('/', "bin").substringBefore(';').ifBlank { "bin" }
        val f = File(rt.hermesHome, "mcp/media/${System.currentTimeMillis()}.$ext").also { it.parentFile?.mkdirs() }
        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        "[$kind $mime saved at ${rt.sandbox.guestPath(f)}]"
    } catch (_: Exception) { "[$kind $mime — could not decode]" }

    // ---------------------------------------------------------------- prompt

    fun promptBlock(): String? {
        val list = servers().filter { it.enabled }
        if (list.isEmpty()) return null
        val lines = list.mapNotNull { s ->
            when (val st = statusOf(s.id)) {
                is McpStatus.Connected -> "- ${s.id}: ${st.tools} tools" + (if (s.note.isNotBlank()) " — ${s.note.take(160)}" else "")
                is McpStatus.NeedsAuth -> "- ${s.id}: not signed in (the user must tap Sign in under MCP servers)"
                else -> null
            }
        }
        if (lines.isEmpty()) return null
        return "## MCP servers\nExternal tools from MCP servers are available as mcp__<server>__<tool> functions" +
            (if (allDefs().size > MAX_NATIVE_TOOLS) " (the rest via the `mcp` tool: action=tools to browse, action=call to run)" else "") + ":\n" + lines.joinToString("\n")
    }

    companion object {
        const val MAX_NATIVE_TOOLS = 48

        fun normalizeSchema(s: JSONObject?): JSONObject {
            val o = if (s == null) JSONObject() else JSONObject(s.toString())
            o.remove("\$schema")
            if (!o.has("type")) o.put("type", "object")
            if (o.optString("type") == "object" && !o.has("properties")) o.put("properties", JSONObject())
            return o
        }

        /** `mcp__server__tool` restricted to [A-Za-z0-9_-], ≤64 chars, unique in [used]. */
        fun wireName(d: McpToolDef, used: MutableSet<String>): String {
            fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val prefix = "mcp__${clean(d.server)}__"
            var name = prefix + clean(d.name)
            if (name.length > 64) {
                val h = MessageDigest.getInstance("MD5").digest(d.name.toByteArray()).joinToString("") { "%02x".format(it) }.take(6)
                name = (prefix + clean(d.name)).take(57) + "_" + h
            }
            var n = 2; val base = name
            while (!used.add(name)) { name = base.take(60) + "_$n"; n++ }
            return name
        }
    }
}
