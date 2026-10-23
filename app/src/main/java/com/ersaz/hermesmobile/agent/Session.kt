package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.optStringOrNull
import com.ersaz.hermesmobile.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ToolCall(val id: String, val name: String, val arguments: String) {
    fun toJson() = JSONObject()
        .put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))

    companion object {
        fun fromJson(o: JSONObject): ToolCall {
            val f = o.optJSONObject("function") ?: JSONObject()
            return ToolCall(o.optString("id"), f.optString("name"), f.optString("arguments", "{}"))
        }
    }
}

/** One OpenAI-format chat message (the wire format Hermes uses internally too). */
data class Message(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val reasoning: String? = null,
    val images: List<String> = emptyList(), // data: URIs or https URLs
    val timestamp: Long = System.currentTimeMillis(),
    val localId: String = UUID.randomUUID().toString(),
    val failureReason: String? = null,
    val generatedFiles: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("role", role).put("content", content).put("ts", timestamp).put("local_id", localId)
        failureReason?.let { o.put("failure_reason", it) }
        if (generatedFiles.isNotEmpty()) o.put("generated_files", JSONArray(generatedFiles))
        if (toolCalls.isNotEmpty()) o.put("tool_calls", JSONArray().also { a -> toolCalls.forEach { a.put(it.toJson()) } })
        toolCallId?.let { o.put("tool_call_id", it) }
        name?.let { o.put("name", it) }
        reasoning?.let { o.put("reasoning", it) }
        if (images.isNotEmpty()) o.put("images", JSONArray(images))
        return o
    }

    /** Wire format for /chat/completions. */
    fun toApi(includeImages: Boolean = true): JSONObject {
        val o = JSONObject().put("role", role)
        if (role == "user" && images.isNotEmpty() && includeImages) {
            val parts = JSONArray()
            if (content.isNotEmpty()) parts.put(JSONObject().put("type", "text").put("text", content))
            images.forEach { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it))) }
            o.put("content", parts)
        } else if (role == "assistant" && toolCalls.isNotEmpty()) {
            o.put("content", if (content.isEmpty()) JSONObject.NULL else content)
            o.put("tool_calls", JSONArray().also { a -> toolCalls.forEach { a.put(it.toJson()) } })
        } else {
            o.put("content", content)
        }
        toolCallId?.let { o.put("tool_call_id", it) }
        if (role == "tool" && name != null) o.put("name", name)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = Message(
            role = o.optString("role"),
            content = o.optString("content", ""),
            toolCalls = o.optJSONArray("tool_calls")?.objects()?.map { ToolCall.fromJson(it) } ?: emptyList(),
            toolCallId = o.optStringOrNull("tool_call_id"),
            name = o.optStringOrNull("name"),
            reasoning = o.optStringOrNull("reasoning"),
            images = o.optJSONArray("images")?.strings() ?: emptyList(),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            localId = o.optString("local_id").ifBlank { UUID.randomUUID().toString() },
            failureReason = o.optStringOrNull("failure_reason"),
            generatedFiles = o.optJSONArray("generated_files")?.strings() ?: emptyList(),
        )
    }
}

data class TodoItem(val id: String, val content: String, val status: String)

class Session(
    val id: String = newId(),
    var title: String = "",
    var source: String = "app", // app | terminal | telegram | discord | slack | cron | subagent
    var chatKey: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var model: String = "",
    var systemPrompt: String? = null,
    messages: List<Message> = emptyList(),
    var promptTokens: Long = 0,
    var completionTokens: Long = 0,
    var lastPromptTokens: Long = 0,
    var apiCalls: Int = 0,
    var compressions: Int = 0,
    todos: List<TodoItem> = emptyList(),
    var modelOverride: String? = null,
    var autoCompact: Boolean = true,
    var botId: String? = null, // which Bot Mode profile this belongs to (null = the plain app chat)
) {
    /**
     * The agent appends on a background thread while the UI rebuilds the transcript and the store serializes
     * the session on others. With a plain ArrayList those readers threw ConcurrentModificationException
     * (a crash when it hit the main thread). Copy-on-write iterates over a stable snapshot; appends are rare
     * relative to reads, so the copy cost is negligible.
     */
    val messages: MutableList<Message> = java.util.concurrent.CopyOnWriteArrayList(messages)
    val todos: MutableList<TodoItem> = java.util.concurrent.CopyOnWriteArrayList(todos)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("source", source).put("chat_key", chatKey)
        .put("created_at", createdAt).put("updated_at", updatedAt).put("model", model)
        .put("system_prompt", systemPrompt)
        .put("messages", JSONArray().also { a -> messages.forEach { a.put(it.toJson()) } })
        .put("usage", JSONObject().put("prompt", promptTokens).put("completion", completionTokens)
            .put("last_prompt", lastPromptTokens).put("api_calls", apiCalls).put("compressions", compressions))
        .put("todos", JSONArray().also { a ->
            todos.forEach { a.put(JSONObject().put("id", it.id).put("content", it.content).put("status", it.status)) }
        })
        .put("auto_compact", autoCompact)
        .put("model_override", modelOverride)
        .put("bot_id", botId)

    /** Resume only the most recent failed user turn; preserve history, attachments and completed tools. */
    fun prepareFailedTurnForRetry(localId: String): Message? {
        val index = messages.indexOfFirst { it.localId == localId && it.role == "user" && it.failureReason != null }
        if (index < 0 || messages.drop(index + 1).any { it.role == "user" && !it.content.startsWith("[SYSTEM:") && !it.content.startsWith("[USER STEER") }) return null
        val failed = messages[index]
        val retry = failed.copy(failureReason = null)
        messages[index] = retry
        return retry
    }

    fun displayTitle(): String = readableSessionTitle(title).ifBlank {
        messages.firstOrNull { it.role == "user" }?.content?.lineSequence()?.firstOrNull()?.take(60)
            ?: "New session"
    }

    companion object {
        fun newId(): String {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "${ts}_${UUID.randomUUID().toString().take(6)}"
        }

        fun fromJson(o: JSONObject): Session {
            val u = o.optJSONObject("usage") ?: JSONObject()
            return Session(
                id = o.getString("id"),
                title = o.optString("title"),
                source = o.optString("source", "app"),
                chatKey = o.optStringOrNull("chat_key"),
                createdAt = o.optLong("created_at"),
                updatedAt = o.optLong("updated_at"),
                model = o.optString("model"),
                systemPrompt = o.optStringOrNull("system_prompt"),
                messages = o.optJSONArray("messages")?.objects()?.map { Message.fromJson(it) }?.toMutableList() ?: mutableListOf(),
                promptTokens = u.optLong("prompt"),
                completionTokens = u.optLong("completion"),
                lastPromptTokens = u.optLong("last_prompt"),
                apiCalls = u.optInt("api_calls"),
                compressions = u.optInt("compressions"),
                todos = o.optJSONArray("todos")?.objects()?.map {
                    TodoItem(it.optString("id"), it.optString("content"), it.optString("status", "pending"))
                }?.toMutableList() ?: mutableListOf(),
                modelOverride = o.optStringOrNull("model_override"),
                autoCompact = o.optBoolean("auto_compact", true),
                botId = o.optStringOrNull("bot_id"),
            )
        }
    }
}

data class SessionSummary(
    val id: String, val title: String, val source: String, val updatedAt: Long, val messageCount: Int,
    val botId: String? = null, val chatKey: String? = null, val preview: String = "", val previewRole: String = "",
)

/** Sessions persisted as JSON files under $HERMES_HOME/sessions (Hermes uses SQLite+FTS5; files keep this simple). */
class SessionStore(private val dir: File) {
    // list()/search() read the cache without the lock while save()/load() write it from other threads.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Session>()

    init { dir.mkdirs() }

    @Synchronized
    fun save(s: Session) {
        s.updatedAt = System.currentTimeMillis()
        cache[s.id] = s
        val tmp = File(dir, "${s.id}.json.tmp")
        tmp.writeText(s.toJson().toString())
        if (!tmp.renameTo(File(dir, "${s.id}.json"))) {
            // Rename can fail on some filesystems when the target exists; never silently drop the save.
            File(dir, "${s.id}.json").writeText(tmp.readText())
            tmp.delete()
        }
    }

    @Synchronized
    fun load(id: String): Session? {
        cache[id]?.let { return it }
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try { Session.fromJson(JSONObject(f.readText())).also { cache[id] = it } } catch (_: Exception) { null }
    }

    @Synchronized
    fun delete(id: String) {
        cache.remove(id)
        File(dir, "$id.json").delete()
    }

    fun list(): List<SessionSummary> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            try {
                val s = cache[f.nameWithoutExtension] ?: Session.fromJson(JSONObject(f.readText()))
                val last = s.messages.lastOrNull { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                SessionSummary(
                    s.id, s.displayTitle(), s.source, s.updatedAt, s.messages.count { it.role == "user" || it.role == "assistant" },
                    s.botId, s.chatKey, last?.content?.replace('\n', ' ')?.take(140).orEmpty(), last?.role.orEmpty(),
                )
            } catch (_: Exception) { null }
        }
        .sortedByDescending { it.updatedAt }

    fun findByTitle(q: String): Session? {
        val hit = list().firstOrNull { it.title.equals(q, true) || it.id == q || it.id.startsWith(q) }
            ?: list().firstOrNull { it.title.contains(q, true) }
        return hit?.let { load(it.id) }
    }

    data class SearchHit(val sessionId: String, val title: String, val updatedAt: Long, val snippets: List<String>)

    /** Cross-session recall (Hermes' session_search, minus the FTS5 index). */
    fun search(query: String, limit: Int = 5, excludeId: String? = null): List<SearchHit> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<Pair<Int, SearchHit>>()
        for (summary in list()) {
            if (summary.id == excludeId) continue
            val s = load(summary.id) ?: continue
            var score = 0
            val snippets = mutableListOf<String>()
            for (m in s.messages) {
                if (m.role != "user" && m.role != "assistant") continue
                val text = m.content
                val lower = text.lowercase()
                val matched = terms.count { it in lower }
                if (matched == 0) continue
                score += matched
                if (snippets.size < 4) {
                    val pos = lower.indexOf(terms.first { it in lower })
                    val start = (pos - 120).coerceAtLeast(0)
                    snippets += "[${m.role}] " + text.substring(start, (pos + 240).coerceAtMost(text.length)).replace('\n', ' ')
                }
            }
            if (summary.title.lowercase().let { t -> terms.any { it in t } }) score += 3
            if (score > 0) hits += score to SearchHit(s.id, s.displayTitle(), s.updatedAt, snippets)
        }
        return hits.sortedByDescending { it.first }.take(limit).map { it.second }
    }
}

/** Tolerate generated JSON titles without rewriting user data. */
fun readableSessionTitle(title: String): String {
    if (!title.trimStart().startsWith("{")) return title
    return runCatching { JSONObject(title).optString("title").ifBlank { title } }.getOrDefault(title)
}
