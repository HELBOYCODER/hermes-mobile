package com.ersaz.hermesmobile.auth

import com.ersaz.hermesmobile.core.NousTokenStore
import com.ersaz.hermesmobile.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Base64

/** Endpoints and client identity of the Nous Portal OAuth device-code flow (same client the Hermes CLI uses). */
object NousPortal {
    const val PORTAL = "https://portal.nousresearch.com"
    const val INFERENCE = "https://inference-api.nousresearch.com/v1"
    const val WELCOME = "https://welcome-api.nousresearch.com/v1"
    const val CLIENT_ID = "hermes-cli"
    const val SCOPE = "inference:invoke"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private val inferenceHosts = setOf("inference-api.nousresearch.com", "welcome-api.nousresearch.com")

    fun authenticatedEndpoint(signedIn: Boolean, preferred: String): String {
        if (!signedIn) throw NousAuthException("Sign in to Nous Portal in Settings. Hermes uses account login, not an API key.", "login_required")
        return safeInferenceUrl(preferred) ?: INFERENCE
    }

    /** The portal may tell us where inference lives; never let it redirect a bearer token off Nous hosts. */
    fun safeInferenceUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val u = URI(url.trim())
            if (u.scheme == "https" && u.host in inferenceHosts) url.trim().trimEnd('/') else null
        } catch (_: Exception) { null }
    }

    fun claims(jwt: String): JSONObject? = try {
        val parts = jwt.split('.')
        if (parts.size < 2) null
        else JSONObject(String(Base64.getUrlDecoder().decode(parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='))))
    } catch (_: Exception) { null }
}

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

class NousAuthException(message: String, val code: String = "", val reloginRequired: Boolean = false) : IOException(message)

/**
 * Nous Portal sign-in: RFC 8628 device authorization, then silent refresh. The access token is a short-lived
 * JWT with the `inference:invoke` scope that the inference API accepts directly as the bearer.
 */
class NousAuth(
    private val http: OkHttpClient,
    private val store: NousTokenStore,
    private val portal: String = NousPortal.PORTAL,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()

    val signedIn: Boolean get() = store.authMode == "nous_oauth" && store.nousAccessToken.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode {
        val json = post("/api/oauth/device/code", FormBody.Builder()
            .add("client_id", NousPortal.CLIENT_ID).add("scope", NousPortal.SCOPE).build(), emptyMap())
        if (json.second !in 200..299) throw NousAuthException("Nous Portal refused the sign-in request (HTTP ${json.second}).")
        val o = json.first
        fun need(k: String) = o.optString(k).takeIf { it.isNotBlank() } ?: throw NousAuthException("Nous Portal reply is missing '$k'.")
        return DeviceCode(
            deviceCode = need("device_code"), userCode = need("user_code"),
            verificationUri = need("verification_uri"),
            verificationUriComplete = o.optString("verification_uri_complete").ifBlank { need("verification_uri") },
            expiresInSeconds = o.optInt("expires_in", 600), intervalSeconds = o.optInt("interval", 5).coerceIn(1, 30),
        )
    }

    /** Poll until the user approves in the browser; stores the tokens. Throws on denial/expiry. */
    suspend fun awaitApproval(dc: DeviceCode, onPoll: () -> Unit = {}) {
        val deadline = now() + dc.expiresInSeconds * 1000L
        var interval = dc.intervalSeconds
        while (now() < deadline) {
            delay(interval * 1000L)
            onPoll()
            val (o, status) = post("/api/oauth/token", FormBody.Builder()
                .add("grant_type", NousPortal.DEVICE_GRANT).add("client_id", NousPortal.CLIENT_ID)
                .add("device_code", dc.deviceCode).build(), emptyMap())
            if (status in 200..299 && o.optString("access_token").isNotBlank()) { storeTokens(o, keepRefresh = null); return }
            when (val err = o.optString("error")) {
                "authorization_pending", "" -> if (status >= 500) throw NousAuthException("Nous Portal is temporarily unavailable (HTTP $status).")
                "slow_down" -> interval = (interval + 5).coerceAtMost(30)
                "access_denied" -> throw NousAuthException("Sign-in was declined in the browser.", err)
                "expired_token" -> throw NousAuthException("The sign-in code expired. Start again.", err)
                else -> throw NousAuthException(o.optString("error_description").ifBlank { "Sign-in failed ($err)." }, err)
            }
        }
        throw NousAuthException("Timed out waiting for approval. If the page showed a CAPTCHA, finish signing in at ${portal}/login and try again.", "timeout")
    }

    /** A bearer that is valid for at least two more minutes (refreshing when needed); null when not signed in. */
    suspend fun accessToken(force: Boolean = false): String? {
        if (!signedIn) return null
        if (!force && store.nousExpiresAt - now() > REFRESH_SKEW_MS) return store.nousAccessToken
        return refreshLock.withLock {
            // A concurrent caller may have refreshed while we waited for the lock.
            if (!force && store.nousExpiresAt - now() > REFRESH_SKEW_MS) return@withLock store.nousAccessToken
            val refresh = store.nousRefreshToken
            if (refresh.isBlank()) throw NousAuthException("Your Nous session expired. Sign in again.", "no_refresh_token", reloginRequired = true)
            val (o, status) = post("/api/oauth/token", FormBody.Builder()
                .add("grant_type", "refresh_token").add("client_id", NousPortal.CLIENT_ID).build(),
                mapOf("x-nous-refresh-token" to refresh))
            when {
                status in 200..299 && o.optString("access_token").isNotBlank() -> storeTokens(o, keepRefresh = refresh)
                status >= 500 || status == 429 -> throw NousAuthException("Nous Portal is temporarily unavailable (HTTP $status).", "unavailable")
                else -> throw NousAuthException("Your Nous session expired. Sign in again.", o.optString("error"), reloginRequired = true)
            }
            store.nousAccessToken
        }
    }

    fun logout() {
        store.authMode = "key"
        store.nousAccessToken = ""
        store.nousRefreshToken = ""
        store.nousExpiresAt = 0
        store.nousScope = ""
        store.nousAccount = ""
    }

    private fun storeTokens(o: JSONObject, keepRefresh: String?) {
        val access = o.getString("access_token")
        val claims = NousPortal.claims(access)
        val expiresIn = o.optLong("expires_in", 900).coerceAtLeast(60)
        val jwtExp = claims?.optLong("exp", 0)?.takeIf { it > 0 }?.times(1000)
        store.nousAccessToken = access
        store.nousRefreshToken = o.optString("refresh_token").ifBlank { keepRefresh ?: store.nousRefreshToken }
        store.nousExpiresAt = jwtExp ?: (now() + expiresIn * 1000)
        store.nousScope = o.optString("scope").ifBlank { NousPortal.SCOPE }
        claims?.let { c ->
            val who = listOf("email", "name", "preferred_username", "sub").firstNotNullOfOrNull { c.optString(it).takeIf { v -> v.isNotBlank() } }
            if (who != null) store.nousAccount = who
        }
        store.authMode = "nous_oauth"
    }

    private suspend fun post(path: String, body: FormBody, headers: Map<String, String>): Pair<JSONObject, Int> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(portal.trimEnd('/') + path).post(body)
                .header("Accept", "application/json").header("User-Agent", "hermes-agent-android")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    // Vercel's bot checkpoint answers non-browser clients with an HTML 403/429 — not a rejected grant.
                    if (json.length() == 0 && (resp.code == 403 || resp.code == 429) && resp.header("x-vercel-mitigated") != null) {
                        throw NousAuthException("Nous Portal's security check blocked this request. Try again in a moment.", "checkpoint")
                    }
                    json to resp.code
                }
            } catch (e: NousAuthException) { throw e }
            catch (e: IOException) { throw NousAuthException("Can't reach Nous Portal: ${e.message}", "network") }
        }

    companion object { const val REFRESH_SKEW_MS = 120_000L }
}

/** Turns an [LlmException] into "this account has no paid access" vs. a real failure. */
fun LlmException.isEntitlementError(): Boolean =
    status in setOf(401, 402, 403) || message.orEmpty().contains("subscription", ignoreCase = true)
