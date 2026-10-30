package com.ersaz.hermesmobile.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * One configured MCP server. Exactly one transport: a remote [url] (Streamable HTTP or legacy SSE) or a local
 * [command] run inside the Linux sandbox (stdio). Serialized in the Claude-Desktop `mcpServers` format, with
 * `enabled`, `note` and `type` as extras, so configs can be pasted in and out and edited from the shell.
 */
/**
 * MCP Hub registry fields. The Claude-Desktop core (url/headers/command/
 * args/env/enabled/transport) is unchanged so configs stay paste-compatible;
 * the Hub extras below round-trip through the same servers.json and are
 * ignored by other Claude-format readers.
 */
data class McpServer(
    val id: String,
    var url: String? = null,
    var headers: Map<String, String> = emptyMap(),
    var command: String? = null,
    var args: List<String> = emptyList(),
    var env: Map<String, String> = emptyMap(),
    var enabled: Boolean = true,
    var note: String = "",
    /** "auto" tries Streamable HTTP then SSE; "http" or "sse" force one. */
    var transport: String = "auto",
    /** Optional pre-registered OAuth client for servers without dynamic registration. */
    var oauthClientId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Hub: display name (defaults to id). */
    var displayName: String = "",
    /** Hub: stdio working directory inside the sandbox guest (default /root). */
    var cwd: String = "/root",
    /** Hub: connect at startup. ON for bundled servers; OFF for custom
     *  entries until their first successful test (the Hub flips it on). */
    var autoStart: Boolean = true,
    /** Hub: true for the bundled defaults (never re-seeded over user edits). */
    var builtin: Boolean = false,
    /** Hub: per-tool gating. Empty allowlist = all tools allowed, minus denylist. */
    var allowedTools: Set<String> = emptySet(),
    var deniedTools: Set<String> = emptySet(),
    /** Hub: JSON-RPC timeout in seconds; 0 = the transport default. */
    var timeoutSec: Int = 0,
    /** Hub: last connection error text (live health lives in McpManager.status). */
    var lastError: String = "",
) {
    val isStdio: Boolean get() = !command.isNullOrBlank()
    val summary: String get() = if (isStdio) (listOf(command!!) + args).joinToString(" ") else url.orEmpty()
    val name: String get() = displayName.ifBlank { id }
    /** Transport badge for the Hub list (stdio | sse | http | auto). */
    val transportBadge: String get() = if (isStdio) "stdio" else transport

    fun toJson(): JSONObject = JSONObject().apply {
        if (isStdio) {
            put("command", command); put("args", JSONArray(args))
            if (env.isNotEmpty()) put("env", JSONObject(env))
        } else {
            put("url", url)
            if (headers.isNotEmpty()) put("headers", JSONObject(headers))
            if (transport != "auto") put("type", transport)
        }
        put("enabled", enabled)
        if (note.isNotBlank()) put("note", note)
        oauthClientId?.let { put("oauth", JSONObject().put("clientId", it)) }
        put("createdAt", createdAt)
        // Hub extras (only when non-default, to keep the file readable).
        if (displayName.isNotBlank() && displayName != id) put("displayName", displayName)
        if (cwd.isNotBlank() && cwd != "/root") put("cwd", cwd)
        if (!autoStart) put("autoStart", false)
        if (builtin) put("builtin", true)
        if (allowedTools.isNotEmpty()) put("allowedTools", JSONArray(allowedTools.sorted()))
        if (deniedTools.isNotEmpty()) put("deniedTools", JSONArray(deniedTools.sorted()))
        if (timeoutSec > 0) put("timeoutSec", timeoutSec)
        if (lastError.isNotBlank()) put("lastError", lastError)
    }

    companion object {
        fun fromJson(id: String, o: JSONObject): McpServer? {
            val url = o.optString("url").ifBlank { o.optString("serverUrl") }.ifBlank { null }
            val command = o.optString("command").ifBlank { null }
            if (url == null && command == null) return null
            fun strMap(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: emptyMap()
            val type = o.optString("type").lowercase()
            fun strSet(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet() } ?: emptySet()
            return McpServer(
                id = id, url = url, headers = strMap("headers"), command = command,
                args = o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                env = strMap("env"),
                enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else !o.optBoolean("disabled", false),
                note = o.optString("note"),
                transport = when { type == "sse" -> "sse"; type in setOf("http", "streamable-http", "streamablehttp") -> "http"; else -> "auto" },
                oauthClientId = o.optJSONObject("oauth")?.optString("clientId")?.ifBlank { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                displayName = o.optString("displayName"),
                cwd = o.optString("cwd").ifBlank { "/root" },
                autoStart = if (o.has("autoStart")) o.optBoolean("autoStart", true) else true,
                builtin = o.optBoolean("builtin", false),
                allowedTools = strSet("allowedTools"),
                deniedTools = strSet("deniedTools"),
                timeoutSec = o.optInt("timeoutSec", 0).coerceAtLeast(0),
                lastError = o.optString("lastError"),
            )
        }
    }
}

/** `$HERMES_HOME/mcp/servers.json` — also visible to the agent's shell. */
class McpStore(private val file: File) {
    init { file.parentFile?.mkdirs() }

    @Synchronized
    fun all(): List<McpServer> = try {
        if (!file.exists()) emptyList()
        else JSONObject(file.readText()).optJSONObject("mcpServers")?.let { o ->
            o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { McpServer.fromJson(k, it) } }.toList()
        }.orEmpty()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun saveAll(list: List<McpServer>) {
        val root = JSONObject().put("mcpServers", JSONObject().also { m -> list.forEach { m.put(it.id, it.toJson()) } })
        val tmp = File(file.parentFile, "servers.json.tmp")
        tmp.writeText(root.toString(2)); tmp.renameTo(file)
    }

    fun get(id: String) = all().firstOrNull { it.id.equals(id, true) }

    @Synchronized
    fun upsert(s: McpServer) { val l = all().toMutableList(); val i = l.indexOfFirst { it.id == s.id }; if (i >= 0) l[i] = s else l += s; saveAll(l) }

    @Synchronized
    fun remove(id: String): Boolean { val l = all().toMutableList(); val r = l.removeAll { it.id.equals(id, true) }; if (r) saveAll(l); return r }

    /** A free server id based on [base] (lowercase, [a-z0-9_-]). */
    fun uniqueId(base: String): String {
        val b = base.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "mcp" }.take(24)
        val taken = all().map { it.id.lowercase() }.toSet()
        if (b !in taken) return b
        var n = 2; while ("$b-$n" in taken) n++
        return "$b-$n"
    }
}

/** Turns whatever the user pastes (URL, Claude-Desktop JSON, `npx …` command) into server configs. */
object McpInput {
    class Parsed(val servers: List<McpServer>, val error: String? = null)

    fun parse(raw: String, nameFree: (String) -> String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(emptyList(), "Paste a server URL, a command or a JSON config.")
        return when {
            text.startsWith("{") -> parseJson(text, nameFree)
            Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(text) -> {
                val s = McpServer(id = nameFree(nameFromUrl(text)), url = text, transport = if (text.trimEnd('/').endsWith("/sse")) "sse" else "auto")
                Parsed(listOf(s))
            }
            else -> {
                val toks = tokenize(text.removePrefix("$ ").trim())
                if (toks.isEmpty()) return Parsed(emptyList(), "Couldn't read that command.")
                // `claude mcp add name -- npx …` style: keep what follows `--`.
                val cmdToks = toks.indexOf("--").takeIf { it >= 0 }?.let { toks.drop(it + 1) } ?: toks
                if (cmdToks.isEmpty()) return Parsed(emptyList(), "Missing command.")
                val name = nameFromCommand(cmdToks)
                Parsed(listOf(McpServer(id = nameFree(name), command = cmdToks.first(), args = cmdToks.drop(1))))
            }
        }
    }

    private fun parseJson(text: String, nameFree: (String) -> String): Parsed {
        val root = try { JSONObject(text) } catch (e: Exception) { return Parsed(emptyList(), "Invalid JSON: ${e.message}") }
        val container = root.optJSONObject("mcpServers") ?: root.optJSONObject("servers")
        val out = mutableListOf<McpServer>()
        if (container != null) {
            for (k in container.keys()) container.optJSONObject(k)?.let { McpServer.fromJson(nameFree(k), it)?.let(out::add) }
        } else if (root.has("url") || root.has("command")) {
            McpServer.fromJson(nameFree(root.optString("name").ifBlank { root.optString("url")?.takeIf { it.isNotBlank() }?.let(::nameFromUrl) ?: nameFromCommand(listOf(root.optString("command"))) }), root)?.let(out::add)
        } else {
            for (k in root.keys()) root.optJSONObject(k)?.let { McpServer.fromJson(nameFree(k), it)?.let(out::add) }
        }
        return if (out.isEmpty()) Parsed(emptyList(), "No MCP server found in that JSON (expected {\"mcpServers\": {…}}).") else Parsed(out)
    }

    fun nameFromUrl(url: String): String {
        val host = try { URI(url).host.orEmpty() } catch (_: Exception) { "" }
        val parts = host.removePrefix("www.").split('.').filter { it.isNotEmpty() }
        val core = parts.dropLast(1).filter { it !in setOf("mcp", "api", "app", "server", "www") }
        return core.lastOrNull() ?: parts.firstOrNull() ?: "mcp"
    }

    fun nameFromCommand(toks: List<String>): String {
        val pkg = toks.drop(1).firstOrNull { !it.startsWith("-") } ?: toks.firstOrNull().orEmpty()
        return pkg.substringAfterLast('/').removeSuffix("-mcp").removePrefix("mcp-").removePrefix("server-").substringBefore('@').ifBlank { "mcp" }
    }

    /** Shell-like split honouring single and double quotes. */
    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q: Char? = null; var has = false
        for (c in s) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' -> { q = c; has = true }
                c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(c)
            }
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
