package com.ersaz.hermesmobile.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Thin client for a running 9Router: dashboard login, API-key creation, provider connect, model catalog. */
class RouterApi(http: OkHttpClient, val baseUrl: String) {
    private val http = http.newBuilder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Log in to the dashboard API and return the session cookie header value. */
    suspend fun login(password: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/auth/login")
            .post(JSONObject().put("password", password).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }.ifBlank { null }
        }
    }

    data class PanelSession(val cookie: String, val password: String)

    suspend fun loginPanel(preferredPassword: String): PanelSession? {
        if (preferredPassword.isNotBlank()) login(preferredPassword)?.let { return PanelSession(it, preferredPassword) }
        if (preferredPassword != "123456") login("123456")?.let { return PanelSession(it, "123456") }
        return null
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

    data class FreeCatalog(val models: List<String>, val live: Boolean)

    suspend fun openCodeFreeModels(cookie: String, key: String): FreeCatalog = withContext(Dispatchers.IO) {
        val live = runCatching {
            val endpoint = "$baseUrl/api/providers/suggested-models".toHttpUrl()
                .newBuilder().addQueryParameter("url", "https://opencode.ai/zen/v1/models")
                .addQueryParameter("type", "opencode-free").build()
            http.newCall(Request.Builder().url(endpoint).header("Cookie", cookie).build()).execute().use { r ->
                if (!r.isSuccessful) emptyList() else {
                    val data = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                    (0 until data.length()).map { data.getJSONObject(it).optString("id") }
                        .filter { it.isNotBlank() }.map { "oc/" + it.removePrefix("oc/").removePrefix("opencode/") }
                }
            }
        }.getOrDefault(emptyList())
        val builtIn = models(key).filter { isOpenCodeFree(it) }
        FreeCatalog((live + builtIn).distinct().sorted(), live.isNotEmpty())
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
        try { http.newCall(Request.Builder().url("$baseUrl/api/auth/status").build()).execute().use { it.isSuccessful } }
        catch (_: Exception) { false }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val FREE = Regex("(?i)(^|[/:_.-])free($|[/:_.-])")

        const val OPENCODE_FREE_PROVIDER = "opencode"

        /** The oc namespace is 9Router's no-auth OpenCode Free lane; ocz is Zen. */
        fun isOpenCodeFree(id: String): Boolean =
            id.substringBefore('/').lowercase() in setOf("oc", "opencode") && id.substringAfter('/', "").isNotBlank()

        /** Free-tier model ids in 9Router's catalog carry "free" in their name (":free", "-free", "/free"). */
        fun isFree(id: String) = FREE.containsMatchIn(id)
    }
}
