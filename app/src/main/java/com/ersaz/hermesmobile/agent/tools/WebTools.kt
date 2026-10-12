package com.ersaz.hermesmobile.agent.tools

import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

object Html {
    private val script = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
    private val block = Regex("(?i)</?(p|div|br|li|h[1-6]|tr|section|article|header|footer|pre|blockquote)[^>]*>")
    private val tag = Regex("<[^>]+>")
    private val ws = Regex("[ \\t\\x0B\\f\\r]+")
    private val nl = Regex("\\n\\s*\\n+")

    fun toText(html: String): String {
        var s = script.replace(html, " ")
        s = block.replace(s, "\n")
        s = tag.replace(s, "")
        s = unescape(s)
        s = ws.replace(s, " ")
        return nl.replace(s, "\n\n").trim()
    }

    fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")

    fun title(html: String) = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()?.let { unescape(it) }
}

class WebSearchTool : Tool(
    "web_search", "web",
    "Search the web. Returns titles, URLs and snippets. Follow up with web_extract to read pages.",
    "🔍",
) {
    override val parameters = obj(
        "query" to str("Search query"),
        "limit" to int("Number of results (default 8)"),
        required = listOf("query"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return@withContext fail("query is required")
        val limit = args.optInt("limit", 8).coerceIn(1, 20)
        val s = ctx.runtime.settings
        val http = ctx.runtime.http
        val results = JSONArray()
        try {
            when {
                s.webSearchBackend == "tavily" && s.tavilyKey.isNotBlank() -> {
                    val body = JSONObject().put("api_key", s.tavilyKey).put("query", q).put("max_results", limit)
                    val req = Request.Builder().url("https://api.tavily.com/search")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    http.newCall(req).execute().use { r ->
                        JSONObject(r.body!!.string()).optJSONArray("results")?.objects()?.forEach {
                            results.put(JSONObject().put("title", it.optString("title")).put("url", it.optString("url")).put("snippet", it.optString("content").take(500)))
                        }
                    }
                }
                s.webSearchBackend == "brave" && s.braveKey.isNotBlank() -> {
                    val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
                        .addQueryParameter("q", q).addQueryParameter("count", limit.toString()).build()
                    val req = Request.Builder().url(url).header("X-Subscription-Token", s.braveKey).header("Accept", "application/json").build()
                    http.newCall(req).execute().use { r ->
                        JSONObject(r.body!!.string()).optJSONObject("web")?.optJSONArray("results")?.objects()?.forEach {
                            results.put(JSONObject().put("title", it.optString("title")).put("url", it.optString("url")).put("snippet", Html.toText(it.optString("description"))))
                        }
                    }
                }
                else -> duckDuckGo(ctx, q, limit, results)
            }
        } catch (e: Exception) {
            return@withContext fail("Search failed: ${e.message}")
        }
        JSONObject().put("query", q).put("results", results).toString()
    }

    private fun duckDuckGo(ctx: ToolContext, q: String, limit: Int, out: JSONArray) {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/")
            .post(okhttp3.FormBody.Builder().add("q", q).build())
            .header("User-Agent", UA).build()
        val html = ctx.runtime.http.newCall(req).execute().use { it.body!!.string() }
        val linkRx = Regex("""(?is)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""")
        val snipRx = Regex("""(?is)class="result__snippet"[^>]*>(.*?)</a>""")
        val links = linkRx.findAll(html).toList()
        val snippets = snipRx.findAll(html).map { Html.toText(it.groupValues[1]) }.toList()
        for ((i, m) in links.withIndex()) {
            var url = Html.unescape(m.groupValues[1])
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            if ("duckduckgo.com/y.js" in url) continue
            out.put(JSONObject().put("title", Html.toText(m.groupValues[2])).put("url", url).put("snippet", snippets.getOrElse(i) { "" }))
            if (out.length() >= limit) break
        }
    }
}

class WebExtractTool : Tool(
    "web_extract", "web",
    "Fetch one or more web pages and return their readable text (HTML stripped). Up to 5 URLs per call.",
    "📄",
) {
    override val parameters = obj(
        "urls" to arr("URLs to fetch", str("URL")),
        "max_chars" to int("Max characters per page (default 15000)"),
        required = listOf("urls"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = coroutineScope {
        val urls = (args.optJSONArray("urls")?.strings() ?: listOfNotNull(args.optString("url").ifBlank { null })).take(5)
        if (urls.isEmpty()) return@coroutineScope fail("urls is required")
        val max = args.optInt("max_chars", 15_000).coerceIn(500, 100_000)
        val pages = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    ctx.runtime.http.newCall(req).execute().use { r ->
                        val type = r.header("Content-Type").orEmpty()
                        val body = r.body?.string().orEmpty()
                        val text = if ("html" in type || body.trimStart().startsWith("<")) Html.toText(body) else body
                        JSONObject().put("url", url).put("status", r.code)
                            .put("title", if ("html" in type) Html.title(body) ?: "" else "")
                            .put("content", com.ersaz.hermesmobile.core.truncateMiddle(text, max))
                    }
                } catch (e: Exception) {
                    JSONObject().put("url", url).put("error", e.message ?: "fetch failed")
                }
            }
        }.awaitAll()
        JSONObject().put("pages", JSONArray(pages)).toString()
    }
}
