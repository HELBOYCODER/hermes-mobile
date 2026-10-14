package com.ersaz.hermesmobile.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Thin client for a running 9Router: dashboard login, API-key creation, provider connect, model catalog. */
class RouterApi(private val http: OkHttpClient, val baseUrl: String) {

    /** Log in to the dashboard API and return the session cookie header value. */
    suspend fun login(password: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/auth/login")
            .post(JSONObject().put("password", password).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }.ifBlank { null }
        }
    }

    suspend fun createKey(cookie: String, name: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/keys").header("Cookie", cookie)
            .post(JSONObject().put("name", name).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("key").ifBlank { null }
        }
    }

    suspend fun connectedProviders(cookie: String): Set<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/providers").header("Cookie", cookie).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext emptySet()
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("connections") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("provider") }.toSet()
        }
    }

    /** Connect a provider that needs no real credential (e.g. OpenCode Zen free tier uses the public key). */
    suspend fun connectKeyProvider(cookie: String, provider: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("provider", provider).put("name", provider).put("apiKey", apiKey)
        val req = Request.Builder().url("$baseUrl/api/providers").header("Cookie", cookie)
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun models(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/v1/models").header("Authorization", "Bearer $key").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
        }
    }

    /** One tiny completion; returns null when the model answers, otherwise the upstream error text. */
    suspend fun probe(key: String, model: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("max_tokens", 8)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        val req = Request.Builder().url("$baseUrl/v1/chat/completions").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON)).build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) null
                else runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()?.take(200) ?: "HTTP ${r.code}"
            }
        } catch (e: Exception) { e.message ?: "request failed" }
    }

    suspend fun isUp(): Boolean = withContext(Dispatchers.IO) {
        try { http.newCall(Request.Builder().url("$baseUrl/v1/models").build()).execute().use { it.code in 200..499 } }
        catch (_: Exception) { false }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val FREE = Regex("(?i)(^|[/:_.-])free($|[/:_.-])")

        /** Free-tier model ids in 9Router's catalog carry "free" in their name (":free", "-free", "/free"). */
        fun isFree(id: String) = FREE.containsMatchIn(id)
    }
}
