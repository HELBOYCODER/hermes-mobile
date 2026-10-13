package com.ersaz.hermesmobile.llm

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.agent.ToolCall
import com.ersaz.hermesmobile.core.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Provider(val id: String, val label: String, val baseUrl: String, val defaultModel: String, val keyHint: String)

object Providers {
    val all = listOf(
        Provider("nous", "Nous Portal", "https://inference-api.nousresearch.com/v1", "Hermes-4-405B", "Nous Portal API key"),
        Provider("openrouter", "OpenRouter (200+ models)", "https://openrouter.ai/api/v1", "nousresearch/hermes-4-405b", "sk-or-…"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4.1", "sk-…"),
        Provider("anthropic", "Anthropic (OpenAI-compatible)", "https://api.anthropic.com/v1", "claude-sonnet-4-5", "sk-ant-…"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza…"),
        Provider("xai", "xAI Grok", "https://api.x.ai/v1", "grok-4", "xai-…"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-…"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_…"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", "key"),
        Provider("ollama", "Ollama (LAN/local)", "http://192.168.1.10:11434/v1", "hermes3", "(none)"),
        Provider("lmstudio", "LM Studio (LAN/local)", "http://192.168.1.10:1234/v1", "local-model", "(none)"),
        Provider("custom", "Custom OpenAI-compatible endpoint", "", "", "key"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.last()
}

class LlmException(message: String, val status: Int = 0, val retryable: Boolean = false) : IOException(message)

data class Usage(val promptTokens: Long, val completionTokens: Long)

data class LlmResponse(
    val content: String,
    val reasoning: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: Usage?,
)

interface StreamSink {
    fun onText(delta: String) {}
    fun onReasoning(delta: String) {}
    fun onToolCallStarted(name: String) {}
}

class LlmClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val provider: String,
) {
    @Volatile private var activeCall: Call? = null

    fun cancel() { activeCall?.cancel() }

    suspend fun chat(
        model: String,
        messages: List<Message>,
        tools: JSONArray? = null,
        stream: Boolean = true,
        maxTokens: Int = 0,
        reasoningEffort: String = "",
        sink: StreamSink? = null,
        temperature: Double? = null,
    ): LlmResponse {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { doChat(model, messages, tools, stream, maxTokens, reasoningEffort, sink, temperature) }
            } catch (e: LlmException) {
                attempt++
                if (!e.retryable || attempt >= 3) throw e
                delay(1500L * attempt * attempt)
            }
        }
    }

    private suspend fun doChat(
        model: String, messages: List<Message>, tools: JSONArray?, stream: Boolean,
        maxTokens: Int, reasoningEffort: String, sink: StreamSink?, temperature: Double?,
    ): LlmResponse {
        if (baseUrl.isBlank()) throw LlmException("No API endpoint configured — open Settings and pick a provider.")
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().also { a -> messages.forEach { a.put(it.toApi()) } })
            .put("stream", stream)
        if (stream) body.put("stream_options", JSONObject().put("include_usage", true))
        if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        if (maxTokens > 0) body.put("max_tokens", maxTokens)
        temperature?.let { body.put("temperature", it) }
        if (reasoningEffort.isNotBlank() && reasoningEffort != "none") {
            if (provider == "openrouter") body.put("reasoning", JSONObject().put("effort", reasoningEffort))
            else body.put("reasoning_effort", reasoningEffort)
        }

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (provider == "openrouter") {
                    header("HTTP-Referer", "https://hermes-agent.nousresearch.com")
                    header("X-Title", "Hermes Agent (Android)")
                }
            }
            .build()

        val call = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build().newCall(req)
        activeCall = call
        val response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try { cont.resumeWith(Result.success(call.execute())) } catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(1500)
                val code = resp.code
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull() ?: err
                throw LlmException("HTTP $code from provider: $msg", code, code == 429 || code >= 500)
            }
            val ctype = resp.header("Content-Type").orEmpty()
            val source = resp.body ?: throw LlmException("Empty response body", retryable = true)
            if (!stream || !ctype.contains("event-stream")) {
                return parseNonStream(JSONObject(source.string()), sink)
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
            var finish: String? = null
            var usage: Usage? = null
            val reader = source.charStream().buffered()
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val chunk = try { JSONObject(data) } catch (_: Exception) { continue }
                chunk.optJSONObject("error")?.let { throw LlmException("Provider error: ${it.optString("message")}", retryable = true) }
                chunk.optJSONObject("usage")?.let {
                    usage = Usage(it.optLong("prompt_tokens"), it.optLong("completion_tokens"))
                }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                val delta = choice.optJSONObject("delta") ?: continue
                val r = delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") }
                if (r.isNotEmpty() && r != "null") { reasoning.append(r); sink?.onReasoning(r) }
                val c = delta.optString("content")
                if (c.isNotEmpty() && c != "null") { content.append(c); sink?.onText(c) }
                delta.optJSONArray("tool_calls")?.objects()?.forEach { tc ->
                    val idx = tc.optInt("index", calls.size)
                    val slot = calls.getOrPut(idx) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    tc.optString("id").takeIf { it.isNotEmpty() && it != "null" }?.let { slot.first.setLength(0); slot.first.append(it) }
                    tc.optJSONObject("function")?.let { f ->
                        val n = f.optString("name")
                        if (n.isNotEmpty() && n != "null") {
                            if (slot.second.isEmpty()) sink?.onToolCallStarted(n)
                            slot.second.setLength(0); slot.second.append(n)
                        }
                        val a = f.optString("arguments")
                        if (a != "null") slot.third.append(a)
                    }
                }
            }
            val toolCalls = calls.values.filter { it.second.isNotEmpty() }.mapIndexed { i, (id, name, args) ->
                ToolCall(id.toString().ifEmpty { "call_${System.currentTimeMillis()}_$i" }, name.toString(), args.toString().ifBlank { "{}" })
            }
            val (cleanContent, thinkTag) = splitThinkTags(content.toString())
            return LlmResponse(cleanContent, (reasoning.toString() + thinkTag).ifBlank { null }, toolCalls, finish, usage)
        }
    }

    private fun parseNonStream(o: JSONObject, sink: StreamSink?): LlmResponse {
        o.optJSONObject("error")?.let { throw LlmException("Provider error: ${it.optString("message")}") }
        val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: throw LlmException("No choices in response")
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val raw = msg.optString("content").takeIf { it != "null" }.orEmpty()
        val (content, think) = splitThinkTags(raw)
        if (content.isNotEmpty()) sink?.onText(content)
        val calls = msg.optJSONArray("tool_calls")?.objects()?.map { ToolCall.fromJson(it) } ?: emptyList()
        val u = o.optJSONObject("usage")
        val reasoning = (msg.optString("reasoning_content").ifEmpty { msg.optString("reasoning") }.takeIf { it != "null" }.orEmpty() + think)
        return LlmResponse(content, reasoning.ifBlank { null }, calls, choice.optString("finish_reason"),
            u?.let { Usage(it.optLong("prompt_tokens"), it.optLong("completion_tokens")) })
    }

    /** Hermes-family models may emit <think>…</think> inline; move it to reasoning. */
    private fun splitThinkTags(s: String): Pair<String, String> {
        val start = s.indexOf("<think>")
        val end = s.indexOf("</think>")
        if (start < 0 || end < start) return s to ""
        val think = s.substring(start + 7, end)
        return (s.substring(0, start) + s.substring(end + 8)).trim() to think
    }

    /** GET /models — used by the model picker. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/models")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw LlmException("HTTP ${resp.code} listing models")
            val o = JSONObject(resp.body?.string().orEmpty())
            (o.optJSONArray("data") ?: JSONArray()).objects().map { it.optString("id") }.filter { it.isNotEmpty() }.sorted()
        }
    }
}
