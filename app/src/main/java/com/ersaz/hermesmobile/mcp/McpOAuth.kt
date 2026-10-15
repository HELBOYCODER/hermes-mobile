package com.ersaz.hermesmobile.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class McpOAuthException(message: String) : IOException(message)

/** Where to authenticate: the authorization server's endpoints for one MCP server. */
data class OAuthMeta(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopes: List<String>,
    val resource: String,
)

/** Stored OAuth state per MCP server id (tokens, the registered client, discovered endpoints). */
class McpAuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)
    fun get(id: String): JSONObject = try { JSONObject(prefs.getString(id, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun put(id: String, o: JSONObject) { prefs.edit().putString(id, o.toString()).apply() }
    fun clearTokens(id: String) { put(id, get(id).also { it.remove("access_token"); it.remove("refresh_token"); it.remove("expires_at") }) }
    fun remove(id: String) { prefs.edit().remove(id).apply() }
    fun hasToken(id: String) = get(id).optString("access_token").isNotBlank()
}

/** Finds the authorization server for an MCP server (RFC 9728 protected-resource metadata + RFC 8414). */
class McpDiscovery(private val http: OkHttpClient) {
    suspend fun discover(serverUrl: String, challenge: String?): OAuthMeta = withContext(Dispatchers.IO) {
        val origin = originOf(serverUrl)
        var authServer: String? = null
        var scopes: List<String> = emptyList()
        var resource = canonicalResource(serverUrl)

        val metaUrl = challenge?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val challengeScope = challenge?.let { Regex("scope=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val candidates = listOfNotNull(
            metaUrl,
            origin + "/.well-known/oauth-protected-resource" + (pathOf(serverUrl).takeIf { it != "/" } ?: ""),
            "$origin/.well-known/oauth-protected-resource",
        )
        for (u in candidates) {
            val j = getJson(u) ?: continue
            authServer = j.optJSONArray("authorization_servers")?.optString(0)?.ifBlank { null }
            j.optString("resource").ifBlank { null }?.let { resource = it }
            scopes = j.optJSONArray("scopes_supported")?.strings().orEmpty()
            if (authServer != null) break
        }
        val asBase = (authServer ?: origin).trimEnd('/')
        val asPath = pathOf(asBase).takeIf { it != "/" } ?: ""
        val asOrigin = originOf(asBase)
        val metaCandidates = listOf(
            "$asOrigin/.well-known/oauth-authorization-server$asPath",
            "$asOrigin/.well-known/openid-configuration$asPath",
            "$asBase/.well-known/openid-configuration",
            "$asOrigin/.well-known/oauth-authorization-server",
        ).distinct()
        var meta: JSONObject? = null
        for (u in metaCandidates) { meta = getJson(u); if (meta != null && meta.has("authorization_endpoint")) break else meta = null }
        val m = meta
        val effectiveScopes = challengeScope?.split(' ')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: scopes
        if (m == null) {
            // Servers without metadata: the MCP spec's default endpoints.
            return@withContext OAuthMeta(asBase, "$asOrigin/authorize", "$asOrigin/token", "$asOrigin/register", effectiveScopes, resource)
        }
        OAuthMeta(
            issuer = m.optString("issuer", asBase),
            authorizationEndpoint = m.getString("authorization_endpoint"),
            tokenEndpoint = m.getString("token_endpoint"),
            registrationEndpoint = m.optString("registration_endpoint").ifBlank { null },
            scopes = effectiveScopes.ifEmpty { m.optJSONArray("scopes_supported")?.strings().orEmpty() }.take(12),
            resource = resource,
        )
    }


    private fun getJson(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
        }
    } catch (_: Exception) { null }

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }
    private fun originOf(url: String) = McpOAuth.originOf(url)
    private fun pathOf(url: String) = McpOAuth.pathOf(url)
    private fun canonicalResource(url: String) = McpOAuth.canonicalResource(url)
}

/**
 * MCP authorization (2025 spec): discover the authorization server from the 401 challenge /
 * protected-resource metadata, register a client dynamically (RFC 7591), then PKCE authorization-code
 * with a loopback redirect (RFC 8252). Tokens refresh silently.
 */
class McpOAuth(private val context: Context, private val http: OkHttpClient, val store: McpAuthStore) {

    suspend fun discover(serverUrl: String, challenge: String?): OAuthMeta = McpDiscovery(http).discover(serverUrl, challenge)

    // ---------------------------------------------------------------- sign-in

    /**
     * Full interactive sign-in. [openUrl] shows the authorization page (browser). Returns when tokens are stored.
     * Throws [McpOAuthException] with a user-readable reason.
     */
    suspend fun authorize(server: McpServer, meta: OAuthMeta, openUrl: (String) -> Unit) {
        val url = server.url ?: throw McpOAuthException("Not a remote server.")
        val callback = LoopbackCallback(com.ersaz.hermesmobile.auth.BrowserAuthCoordinator.returnLink(context, "mcp"))
        try {
            val redirect = callback.redirectUri
            val st = store.get(server.id)
            var clientId = server.oauthClientId ?: st.optString("client_id").ifBlank { null }
            var clientSecret = st.optString("client_secret").ifBlank { null }
            // A client registered for another redirect port/auth server can't be reused.
            if (st.optString("registered_redirect") != redirect || st.optString("issuer") != meta.issuer) {
                if (server.oauthClientId == null) { clientId = null; clientSecret = null }
            }
            if (clientId == null) {
                val reg = meta.registrationEndpoint ?: throw McpOAuthException(
                    "This server doesn't support automatic client registration. Enter an OAuth client id in the server's settings.")
                val (id, secret) = registerClient(reg, redirect)
                clientId = id; clientSecret = secret
            }
            val verifier = randomString(64)
            val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val state = randomString(24)
            val authUrl = buildString {
                append(meta.authorizationEndpoint)
                append(if ('?' in meta.authorizationEndpoint) '&' else '?')
                append("response_type=code&client_id=").append(enc(clientId))
                append("&redirect_uri=").append(enc(redirect))
                append("&code_challenge=").append(challenge).append("&code_challenge_method=S256")
                append("&state=").append(state)
                append("&resource=").append(enc(canonicalResource(url)))
                if (meta.scopes.isNotEmpty()) append("&scope=").append(enc(meta.scopes.joinToString(" ")))
            }
            openUrl(authUrl)
            val params = try { withTimeout(5 * 60_000L) { callback.await(state) } }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw McpOAuthException("Sign-in timed out. Try again.") }
            if (params["error"] != null) throw McpOAuthException("Sign-in failed: ${params["error_description"] ?: params["error"]}")
            if (params["state"] != state) throw McpOAuthException("Sign-in response didn't match the request (state mismatch).")
            val code = params["code"] ?: throw McpOAuthException("The server returned no authorization code.")
            // Android can block the network while we're backgrounded behind the browser: exchange once we're back.
            AppForeground.await()
            val tokens = tokenRequest(meta.tokenEndpoint, FormBody.Builder()
                .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
                .add("client_id", clientId).add("code_verifier", verifier).add("resource", canonicalResource(url))
                .also { b -> clientSecret?.let { b.add("client_secret", it) } }.build())
            persist(server.id, tokens, meta, clientId, clientSecret, redirect)
        } finally { callback.close() }
    }

    // ---------------------------------------------------------------- tokens

    /** A usable access token for [server], refreshing if it's about to expire; null if never signed in. */
    suspend fun accessToken(server: McpServer, force: Boolean = false): String? {
        val st = store.get(server.id)
        val access = st.optString("access_token").ifBlank { return null }
        val exp = st.optLong("expires_at", 0)
        if (!force && (exp == 0L || exp - System.currentTimeMillis() > 60_000)) return access
        val refresh = st.optString("refresh_token").ifBlank { return if (force) null else access }
        val endpoint = st.optString("token_endpoint").ifBlank { return access }
        return try {
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
                .add("client_id", st.optString("client_id")).add("resource", st.optString("resource"))
                .also { b -> st.optString("client_secret").takeIf { it.isNotBlank() }?.let { b.add("client_secret", it) } }.build()
            val tokens = tokenRequest(endpoint, form)
            val meta = OAuthMeta(st.optString("issuer"), st.optString("authorization_endpoint"), endpoint, null, emptyList(), st.optString("resource"))
            persist(server.id, tokens, meta, st.optString("client_id"), st.optString("client_secret").ifBlank { null }, st.optString("registered_redirect"), refresh)
            store.get(server.id).optString("access_token")
        } catch (e: McpOAuthException) {
            store.clearTokens(server.id); null
        }
    }

    fun signOut(id: String) = store.clearTokens(id)

    private fun persist(id: String, t: JSONObject, meta: OAuthMeta, clientId: String, secret: String?, redirect: String, oldRefresh: String? = null) {
        val o = store.get(id)
        o.put("access_token", t.getString("access_token"))
        val rt = t.optString("refresh_token").ifBlank { oldRefresh ?: o.optString("refresh_token") }
        if (rt.isNotBlank()) o.put("refresh_token", rt)
        val ttl = t.optLong("expires_in", 0)
        o.put("expires_at", if (ttl > 0) System.currentTimeMillis() + ttl * 1000 else 0L)
        o.put("client_id", clientId); if (secret != null) o.put("client_secret", secret)
        o.put("token_endpoint", meta.tokenEndpoint); o.put("authorization_endpoint", meta.authorizationEndpoint)
        o.put("issuer", meta.issuer); o.put("resource", meta.resource); o.put("registered_redirect", redirect)
        store.put(id, o)
    }

    private suspend fun registerClient(endpoint: String, redirect: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_name", "Hermes Agent (Android)")
            .put("redirect_uris", JSONArray().put(redirect))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")
        val req = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw McpOAuthException("Client registration was refused (HTTP ${r.code}). ${text.take(160)}")
            val j = JSONObject(text)
            val id = j.optString("client_id").ifBlank { throw McpOAuthException("Registration returned no client_id.") }
            id to j.optString("client_secret").ifBlank { null }
        }
    }

    private suspend fun tokenRequest(endpoint: String, form: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                if (!r.isSuccessful || j.optString("access_token").isBlank())
                    throw McpOAuthException("Token request failed (HTTP ${r.code}): ${j.optString("error_description").ifBlank { j.optString("error").ifBlank { text.take(160) } }}")
                j
            }
        } catch (e: McpOAuthException) { throw e } catch (e: IOException) { throw McpOAuthException("Can't reach the token endpoint: ${e.message}") }
    }

    companion object {
        private val rng = SecureRandom()
        fun randomString(n: Int): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            return buildString { repeat(n) { append(chars[rng.nextInt(chars.length)]) } }
        }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun originOf(url: String): String = URI(url).let { "${it.scheme}://${it.authority}" }
        fun pathOf(url: String): String = URI(url).path.orEmpty().ifEmpty { "/" }.trimEnd('/').ifEmpty { "/" }
        /** RFC 8707: scheme://host[:port]/path without query/fragment or trailing slash. */
        fun canonicalResource(url: String): String = URI(url).let { u -> "${u.scheme.lowercase()}://${u.authority.lowercase()}${u.path.orEmpty().trimEnd('/')}" }
        private fun JSONArray.strings() = (0 until length()).map { optString(it) }
    }
}

/** One-shot HTTP server on 127.0.0.1 that receives the browser redirect. */
class LoopbackCallback(private val returnUri: String? = null) {
    private val server: ServerSocket = bind()
    val redirectUri: String get() = "http://127.0.0.1:${server.localPort}/callback"

    private fun bind(): ServerSocket {
        for (p in 54546..54556) try { return ServerSocket(p, 4, InetAddress.getByName("127.0.0.1")) } catch (_: IOException) {}
        return ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    }

    suspend fun await(expectedState: String? = null): Map<String, String> = withContext(Dispatchers.IO) {
        while (true) {
            val sock = try { kotlinx.coroutines.runInterruptible { server.accept() } } catch (e: IOException) { throw CancellationException("closed") }
            sock.use { s ->
                s.soTimeout = 3000
                val line = try { kotlinx.coroutines.runInterruptible {
                    val input = s.getInputStream()
                    val request = StringBuilder()
                    while (request.length < 8192) {
                        val ch = input.read()
                        if (ch < 0 || ch == 10) break
                        request.append(ch.toChar())
                    }
                    request.toString()
                } } catch (_: java.net.SocketTimeoutException) { return@use }
                if (line.length >= 8192 || !line.startsWith("GET ")) { respond(s, 400, "Invalid request"); return@use }
                val target = line.split(' ').getOrNull(1).orEmpty()
                if (target.substringBefore('?') != "/callback") { respond(s, 404, "Not found"); return@use }
                val q = target.substringAfter('?', "")
                val params = q.split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                if (expectedState != null && params["state"] != expectedState) {
                    respond(s, 400, "Sign-in response does not match this request.")
                    return@use
                }
                val ok = params["error"] == null
                val link = returnUri?.let {
                    val safe = it.replace("&", "&amp;").replace("'", "&#39;").replace("<", "&lt;")
                    "<p><a id='return' href='$safe'>Return to Hermes</a></p><script>document.getElementById('return').click()</script>"
                }.orEmpty()
                respond(s, 200, "<html><body style='font-family:sans-serif;text-align:center;padding:48px'><h2>${if (ok) "Approval received" else "Sign-in declined"}</h2><p>Return to Hermes to finish connecting.</p>$link</body></html>")
                return@withContext params
            }
        }
        @Suppress("UNREACHABLE_CODE") emptyMap()
    }

    private fun respond(s: java.net.Socket, code: Int, body: String) {
        val b = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush()
        }
    }

    fun close() { try { server.close() } catch (_: Exception) {} }
}

/** Tracks whether any of our activities is visible (set up in [com.ersaz.hermesmobile.HermesApp]). */
object AppForeground {
    @Volatile var started = 0
    val inForeground get() = started > 0
    suspend fun await(timeoutMs: Long = 120_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!inForeground && System.currentTimeMillis() < end) kotlinx.coroutines.delay(300)
    }
}

fun Context.openInBrowser(url: String) {
    com.ersaz.hermesmobile.auth.BrowserAuthCoordinator.open(this, url)
}
