package com.ersaz.hermesmobile.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenRouter "Sign in with OpenRouter", implemented from the official
 * OpenRouter documentation (openrouter.ai/docs/guides/overview/auth/oauth,
 * checked 2026-10-30) — Hermes' own code, no other project's code.
 *
 * Verified flow (OAuth PKCE):
 *  1. Send the user to https://openrouter.ai/auth with a S256
 *     code_challenge. The docs' callback variant needs a website of
 *     Hermes' own to redirect to, which this app does not have, and
 *     whether OpenRouter would accept an Android intent:// callback URL
 *     is unverified — so this implementation uses the docs' HEADLESS
 *     mode instead: callback_url is omitted, OpenRouter displays a
 *     one-time authorization code on screen after approval, and the
 *     user pastes THAT CODE (not an API key — it is single-use and
 *     expires after 10 minutes) into Hermes.
 *  2. POST https://openrouter.ai/api/v1/auth/keys with
 *     {code, code_verifier, code_challenge_method: "S256"} and receive
 *     a user-controlled API key tied to the user's own account. Their
 *     requests bill to their own OpenRouter credits; Hermes never sees
 *     their OpenRouter password.
 *
 * The returned key is stored only via the app's existing provider-key
 * setting (same store as every other provider key) and is never logged.
 * The response field is read tolerantly ("key", then "api_key", then any
 * top-level "sk-or-…" string) because the fetched doc page renders the
 * exchange example without its body text; if no key is found, login
 * fails with an error instead of storing anything.
 */
object OpenRouterAuth {
    const val AUTH_URL = "https://openrouter.ai/auth"
    const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"

    data class Pkce(val verifier: String, val challenge: String)

    fun newPkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
        return Pkce(verifier, challenge)
    }

    /** Headless-mode authorize URL (no callback_url — see the class doc). */
    fun authUrl(pkce: Pkce): String =
        "$AUTH_URL?code_challenge=${pkce.challenge}&code_challenge_method=S256"

    /** Exchanges a one-time authorization code for the user's own API key. */
    suspend fun exchange(http: OkHttpClient, code: String, pkce: Pkce): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("code", code.trim())
            .put("code_verifier", pkce.verifier)
            .put("code_challenge_method", "S256")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(EXCHANGE_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("OpenRouter refused the code (HTTP ${resp.code}). Codes are single-use and expire after 10 minutes — start the login again for a fresh code.")
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("OpenRouter reply was not JSON.")
            json.optString("key").takeIf { it.isNotBlank() }
                ?: json.optString("api_key").takeIf { it.isNotBlank() }
                ?: json.keys().asSequence()
                    .mapNotNull { k -> json.optString(k).takeIf { it.startsWith("sk-or-") } }
                    .firstOrNull()
                ?: throw IllegalStateException("OpenRouter reply contained no API key.")
        }
    }
}
