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
            (0 until arr.length()).mapNotNull { parseModelId(arr, it) }.filter { it.isNotBlank() }
        }
    }

    /**
     * One catalog entry: an id string, or an object with "id" (OpenAI-style) or "name".
     * The old code called getJSONObject on every entry, so a single string entry threw and
     * the whole suggested-models result collapsed to an empty list — silently.
     */
    private fun parseModelId(arr: JSONArray, i: Int): String? = runCatching {
        when (val v = arr[i]) {
            is String -> v
            is JSONObject -> v.optString("id").ifBlank { v.optString("name") }
            else -> null
        }
    }.getOrNull()

    /** Id arrays may arrive under "data" (OpenAI-style) or "models" (9Router variants). */
    private fun modelArray(body: String): JSONArray {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return JSONArray()
        return o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
    }

    /** Suggested OpenCode free models from the dashboard, with reachability reported separately. */
    suspend fun suggestedModels(cookie: String): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "$baseUrl/api/providers/suggested-models".toHttpUrl()
                .newBuilder().addQueryParameter("url", "https://opencode.ai/zen/v1/models")
                .addQueryParameter("type", "opencode-free").build()
            http.newCall(Request.Builder().url(endpoint).header("Cookie", cookie).build()).execute().use { r ->
                if (!r.isSuccessful) false to emptyList()
                else {
                    val body = r.body?.string().orEmpty()
                    val arr = modelArray(body)
                    true to (0 until arr.length())
                        .mapNotNull { parseModelId(arr, it) }
                        .filter { it.isNotBlank() }.map { normalizeOpenCodeId(it) }
                }
            }
        }.getOrDefault(false to emptyList())
    }

    /** Namespace histogram of /v1/models, for diagnosis only (no model is invented from it). */
    suspend fun modelNamespaces(key: String): Map<String, Int> =
        models(key).groupingBy { if ('/' in it) it.substringBefore('/').lowercase() else "(bare)" }.eachCount()

    data class FreeCatalog(
        val models: List<String>,
        val live: Boolean,
        val liveCount: Int = 0,
        val builtinCount: Int = 0,
        val liveReachable: Boolean = false,
    )

    suspend fun openCodeFreeModels(cookie: String, key: String): FreeCatalog = withContext(Dispatchers.IO) {
        val (reachable, liveRaw) = suggestedModels(cookie)
        val builtIn = models(key).filter { isOpenCodeFree(it) }.map { normalizeOpenCodeId(it) }
        FreeCatalog((liveRaw + builtIn).distinct().sorted(), liveRaw.isNotEmpty(),
            liveCount = liveRaw.size, builtinCount = builtIn.size, liveReachable = reachable)
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

        /**
         * The oc namespace is 9Router's no-auth OpenCode Free lane; ocz is Zen, and some
         * 9Router builds expose the same lane as "zen/…". All three are the OpenCode free
         * lane — the old set dropped zen/ocz ids entirely, so a Zen-only catalog came back
         * empty even though the router was serving free models.
         */
        fun isOpenCodeFree(id: String): Boolean =
            id.substringBefore('/').lowercase() in setOf("oc", "opencode", "zen", "ocz") && id.substringAfter('/', "").isNotBlank()

        /** One canonical id for the free lane: strip any lane prefix, re-prefix with oc/. */
        fun normalizeOpenCodeId(id: String): String {
            var bare = id.trim()
            for (prefix in listOf("oc/", "opencode/", "zen/", "ocz/")) {
                if (bare.lowercase().startsWith(prefix)) { bare = bare.substring(prefix.length); break }
            }
            return "oc/$bare"
        }

        /** Free-tier model ids in 9Router's catalog carry "free" in their name (":free", "-free", "/free"). */
        fun isFree(id: String) = FREE.containsMatchIn(id)
    }
}
