package com.ersaz.hermesmobile.gateway

import android.util.Base64
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.core.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

abstract class PlatformAdapter(protected val rt: HermesRuntime, protected val gw: GatewayManager, val name: String) {
    val status = MutableStateFlow("starting…")
    val connected = MutableStateFlow(false)
    protected var job: Job? = null
    abstract val maxLen: Int

    abstract fun start()
    open fun stop() { job?.cancel(); connected.value = false; status.value = "stopped" }

    abstract suspend fun sendText(chatId: String, text: String, threadId: String?)
    open suspend fun typing(chatId: String, threadId: String?) {}
    open suspend fun sendFile(chatId: String, file: File, caption: String?, threadId: String?) =
        sendText(chatId, "📎 ${file.name} (file upload not supported on $name)", threadId)

    fun outputFor(chatId: String, threadId: String?): ChatOutput = object : ChatOutput {
        override suspend fun send(text: String) {
            for (chunk in chunk(text, maxLen)) sendText(chatId, chunk, threadId)
        }
        override suspend fun typing() = this@PlatformAdapter.typing(chatId, threadId)
        override suspend fun sendFile(file: File, caption: String?) = this@PlatformAdapter.sendFile(chatId, file, caption, threadId)
    }

    protected fun json(body: JSONObject) = body.toString().toRequestBody("application/json".toMediaType())

    companion object {
        /** Split on paragraph/line boundaries, keeping fenced code blocks balanced when cut. */
        fun chunk(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > max) {
                var cut = rest.lastIndexOf("\n\n", max - 8).takeIf { it > max / 3 }
                    ?: rest.lastIndexOf('\n', max - 8).takeIf { it > max / 3 }
                    ?: rest.lastIndexOf(' ', max - 8).takeIf { it > max / 3 } ?: (max - 8)
                var part = rest.substring(0, cut)
                rest = rest.substring(cut).trimStart('\n')
                if (part.split("```").size % 2 == 0) { part += "\n```"; rest = "```\n$rest" }
                out += part
                if (cut <= 0) cut = 1
            }
            if (rest.isNotBlank()) out += rest
            return out
        }
    }
}

// ============================================================== Telegram

class TelegramAdapter(rt: HermesRuntime, gw: GatewayManager, private val token: String) : PlatformAdapter(rt, gw, "telegram") {
    override val maxLen = 4000
    private val api = "https://api.telegram.org/bot$token"
    private var botId = 0L
    private var botUser = ""
    private val longPoll = rt.http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    private fun call(method: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url("$api/$method").post(json(body)).build()
        longPoll.newCall(req).execute().use { r -> return JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }
    }

    override fun start() {
        job = rt.scope.launch(Dispatchers.IO) {
            var offset = 0L
            var backoff = 2000L
            while (isActive) {
                try {
                    if (botId == 0L) {
                        val me = call("getMe", JSONObject())
                        if (!me.optBoolean("ok")) { status.value = "auth failed: ${me.optString("description")}"; delay(30_000); continue }
                        botId = me.getJSONObject("result").optLong("id")
                        botUser = me.getJSONObject("result").optString("username")
                        registerCommands()
                        status.value = "connected as @$botUser"; connected.value = true
                        gw.log("telegram connected as @$botUser")
                    }
                    val res = call("getUpdates", JSONObject().put("offset", offset).put("timeout", 50)
                        .put("allowed_updates", JSONArray(listOf("message", "edited_message"))))
                    if (!res.optBoolean("ok")) { status.value = "error: ${res.optString("description")}"; delay(5000); continue }
                    backoff = 2000
                    for (u in res.optJSONArray("result")?.objects().orEmpty()) {
                        offset = u.optLong("update_id") + 1
                        val m = u.optJSONObject("message") ?: continue
                        handle(m)
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    connected.value = false
                    status.value = "reconnecting: ${e.message?.take(80)}"
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        }
    }

    private fun registerCommands() {
        val cmds = JSONArray()
        Commands.defs.filter { it.name !in setOf("skin") }.take(30).forEach {
            cmds.put(JSONObject().put("command", it.name).put("description", it.description.take(255)))
        }
        runCatching { call("setMyCommands", JSONObject().put("commands", cmds)) }
    }

    private fun handle(m: JSONObject) {
        val chat = m.getJSONObject("chat")
        val from = m.optJSONObject("from") ?: return
        if (from.optBoolean("is_bot")) return
        val isDm = chat.optString("type") == "private"
        var text = m.optString("text").ifEmpty { m.optString("caption") }
        val mentioned = botUser.isNotEmpty() && text.contains("@$botUser", true)
        val replyToBot = m.optJSONObject("reply_to_message")?.optJSONObject("from")?.optLong("id") == botId
        if (!isDm && rt.settings.telegramRequireMention && !mentioned && !replyToBot && !text.startsWith("/")) return
        text = text.replace("@$botUser", "", ignoreCase = true).trim()
        val images = mutableListOf<String>()
        m.optJSONArray("photo")?.objects()?.lastOrNull()?.optString("file_id")?.let { id ->
            runCatching { downloadImage(id) }.getOrNull()?.let { images += it }
        }
        if (m.has("voice") || m.has("audio")) {
            if (text.isBlank()) text = "[The user sent a voice message; voice transcription is not available on this device yet.]"
        }
        m.optJSONObject("document")?.let { d -> text += "\n[Attached document: ${d.optString("file_name")} — not downloaded]" }
        val thread = if (m.optBoolean("is_topic_message")) m.optLong("message_thread_id").toString() else null
        gw.onIncoming(IncomingMessage("telegram", chat.optLong("id").toString(), from.optLong("id").toString(),
            from.optString("username").ifBlank { from.optString("first_name") }, text, images, isDm, thread,
            handle = from.optString("username").takeIf { it.isNotBlank() }))
    }

    private fun downloadImage(fileId: String): String? {
        val f = call("getFile", JSONObject().put("file_id", fileId)).optJSONObject("result") ?: return null
        val url = "https://api.telegram.org/file/bot$token/${f.optString("file_path")}"
        val bytes = rt.http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } ?: return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override suspend fun sendText(chatId: String, text: String, threadId: String?) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("chat_id", chatId).put("text", text).put("parse_mode", "Markdown")
        threadId?.let { body.put("message_thread_id", it.toLong()) }
        val r = call("sendMessage", body)
        if (!r.optBoolean("ok")) { body.remove("parse_mode"); call("sendMessage", body) } // markdown parse error → plain
        Unit
    }

    override suspend fun typing(chatId: String, threadId: String?) = withContext(Dispatchers.IO) {
        runCatching { call("sendChatAction", JSONObject().put("chat_id", chatId).put("action", "typing")) }; Unit
    }

    override suspend fun sendFile(chatId: String, file: File, caption: String?, threadId: String?) = withContext(Dispatchers.IO) {
        val (method, field) = when (file.extension.lowercase()) {
            "wav", "mp3", "m4a", "ogg", "oga" -> "sendAudio" to "audio"
            "png", "jpg", "jpeg", "webp" -> "sendPhoto" to "photo"
            else -> "sendDocument" to "document"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(field, file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .apply { caption?.let { addFormDataPart("caption", it.take(1000)) }; threadId?.let { addFormDataPart("message_thread_id", it) } }
            .build()
        rt.http.newCall(Request.Builder().url("$api/$method").post(body).build()).execute().close()
    }
}

// ============================================================== Discord

class DiscordAdapter(rt: HermesRuntime, gw: GatewayManager, private val token: String) : PlatformAdapter(rt, gw, "discord") {
    override val maxLen = 1990
    private val api = "https://discord.com/api/v10"
    private var ws: WebSocket? = null
    private var heartbeat: Job? = null
    @Volatile private var seq: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var botId = ""
    private var stopped = false

    override fun start() {
        stopped = false
        connect(resume = false)
    }

    override fun stop() {
        stopped = true
        heartbeat?.cancel(); ws?.close(1000, "bye"); super.stop()
    }

    private fun connect(resume: Boolean) {
        val url = (if (resume) resumeUrl else null) ?: "wss://gateway.discord.gg"
        ws = rt.http.newWebSocket(Request.Builder().url("$url/?v=10&encoding=json").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onPayload(webSocket, JSONObject(text), resume)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect("failure: ${t.message}", true)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                reconnect("closed $code $reason", code !in setOf(4004, 4010, 4011, 4012, 4013, 4014))
        })
    }

    private fun reconnect(why: String, canResume: Boolean) {
        connected.value = false
        heartbeat?.cancel()
        if (stopped) return
        status.value = "reconnecting ($why)"
        if (why.contains("4004") || why.contains("4014")) {
            status.value = if (why.contains("4014")) "enable MESSAGE CONTENT intent in the Developer Portal" else "invalid bot token"
            return
        }
        rt.scope.launch { delay(5000); if (!stopped) connect(canResume && sessionId != null) }
    }

    private fun onPayload(socket: WebSocket, p: JSONObject, resuming: Boolean) {
        if (!p.isNull("s")) seq = p.optLong("s")
        when (p.optInt("op")) {
            10 -> {
                val interval = p.getJSONObject("d").optLong("heartbeat_interval", 41_250)
                heartbeat?.cancel()
                heartbeat = rt.scope.launch {
                    delay((interval * Math.random()).toLong())
                    while (isActive) {
                        socket.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
                        delay(interval)
                    }
                }
                if (resuming && sessionId != null) {
                    socket.send(JSONObject().put("op", 6).put("d", JSONObject().put("token", token).put("session_id", sessionId).put("seq", seq)).toString())
                } else {
                    val intents = 1 or 512 or 4096 or 32768 // GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
                    socket.send(JSONObject().put("op", 2).put("d", JSONObject().put("token", token).put("intents", intents)
                        .put("properties", JSONObject().put("os", "android").put("browser", "hermes").put("device", "hermes"))).toString())
                }
            }
            1 -> socket.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
            7 -> socket.close(4000, "reconnect requested")
            9 -> { sessionId = null; rt.scope.launch { delay(2000); socket.close(4000, "invalid session") } }
            0 -> dispatch(p.optString("t"), p.optJSONObject("d") ?: JSONObject())
        }
    }

    private fun dispatch(type: String, d: JSONObject) {
        when (type) {
            "READY" -> {
                sessionId = d.optString("session_id"); resumeUrl = d.optString("resume_gateway_url").ifBlank { null }
                val u = d.optJSONObject("user")
                botId = u?.optString("id").orEmpty()
                connected.value = true; status.value = "connected as ${u?.optString("username")}"
                gw.log("discord connected as ${u?.optString("username")}")
            }
            "RESUMED" -> { connected.value = true; status.value = "connected (resumed)" }
            "MESSAGE_CREATE" -> {
                val author = d.optJSONObject("author") ?: return
                if (author.optBoolean("bot")) return
                val isDm = !d.has("guild_id")
                var text = d.optString("content")
                val mentioned = d.optJSONArray("mentions")?.objects()?.any { it.optString("id") == botId } == true
                if (!isDm && rt.settings.discordRequireMention && !mentioned) return
                text = text.replace("<@$botId>", "").replace("<@!$botId>", "").trim()
                val images = d.optJSONArray("attachments")?.objects()
                    ?.filter { it.optString("content_type").startsWith("image/") }?.map { it.optString("url") } ?: emptyList()
                gw.onIncoming(IncomingMessage("discord", d.optString("channel_id"), author.optString("id"),
                    author.optString("username"), text, images, isDm, handle = author.optString("username").takeIf { it.isNotBlank() }))
            }
        }
    }

    private fun rest(path: String, body: JSONObject) {
        val req = Request.Builder().url("$api$path").header("Authorization", "Bot $token").post(json(body)).build()
        rt.http.newCall(req).execute().use { r ->
            if (r.code == 429) Thread.sleep((JSONObject(r.body?.string() ?: "{}").optDouble("retry_after", 1.0) * 1000).toLong())
        }
    }

    override suspend fun sendText(chatId: String, text: String, threadId: String?) = withContext(Dispatchers.IO) {
        rest("/channels/${threadId ?: chatId}/messages", JSONObject().put("content", text))
    }

    override suspend fun typing(chatId: String, threadId: String?) = withContext(Dispatchers.IO) {
        runCatching {
            rt.http.newCall(Request.Builder().url("$api/channels/$chatId/typing").header("Authorization", "Bot $token")
                .post(ByteArray(0).toRequestBody()).build()).execute().close()
        }; Unit
    }

    override suspend fun sendFile(chatId: String, file: File, caption: String?, threadId: String?) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", JSONObject().put("content", caption.orEmpty()).toString())
            .addFormDataPart("files[0]", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        rt.http.newCall(Request.Builder().url("$api/channels/${threadId ?: chatId}/messages")
            .header("Authorization", "Bot $token").post(body).build()).execute().close()
    }
}

// ============================================================== Slack (Socket Mode)

class SlackAdapter(rt: HermesRuntime, gw: GatewayManager, private val botToken: String, private val appToken: String) :
    PlatformAdapter(rt, gw, "slack") {
    override val maxLen = 3900
    private var ws: WebSocket? = null
    private var stopped = false
    private var botUserId = ""

    override fun start() {
        stopped = false
        rt.scope.launch(Dispatchers.IO) { connect() }
    }

    override fun stop() { stopped = true; ws?.close(1000, "bye"); super.stop() }

    private fun api(method: String, token: String, body: JSONObject = JSONObject()): JSONObject {
        val req = Request.Builder().url("https://slack.com/api/$method").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        rt.http.newCall(req).execute().use { return JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
    }

    private suspend fun connect() {
        try {
            if (botUserId.isEmpty()) botUserId = api("auth.test", botToken).optString("user_id")
            val open = api("apps.connections.open", appToken)
            if (!open.optBoolean("ok")) { status.value = "auth failed: ${open.optString("error")}"; return }
            ws = rt.http.newWebSocket(Request.Builder().url(open.getString("url")).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected.value = true; status.value = "connected (socket mode)"; gw.log("slack connected")
                }
                override fun onMessage(webSocket: WebSocket, text: String) = onEnvelope(webSocket, JSONObject(text))
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry("failure: ${t.message}")
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry("closed $code")
            })
        } catch (e: Exception) { retry(e.message ?: "error") }
    }

    private fun retry(why: String) {
        connected.value = false
        if (stopped) return
        status.value = "reconnecting ($why)"
        rt.scope.launch(Dispatchers.IO) { delay(5000); if (!stopped) connect() }
    }

    private fun onEnvelope(socket: WebSocket, env: JSONObject) {
        env.optString("envelope_id").takeIf { it.isNotEmpty() }?.let { socket.send(JSONObject().put("envelope_id", it).toString()) }
        when (env.optString("type")) {
            "disconnect" -> socket.close(1000, "refresh")
            "events_api" -> {
                val e = env.optJSONObject("payload")?.optJSONObject("event") ?: return
                if (e.has("bot_id") || e.has("subtype")) return
                val type = e.optString("type")
                val isDm = e.optString("channel_type") == "im"
                if (type == "message" && !isDm) return // channels are handled via app_mention
                if (type != "message" && type != "app_mention") return
                val text = e.optString("text").replace("<@$botUserId>", "").trim()
                val thread = if (isDm) null else (e.optString("thread_ts").ifBlank { e.optString("ts") })
                gw.onIncoming(IncomingMessage("slack", e.optString("channel"), e.optString("user"), e.optString("user"), text, emptyList(), isDm, thread))
            }
        }
    }

    override suspend fun sendText(chatId: String, text: String, threadId: String?) = withContext(Dispatchers.IO) {
        api("chat.postMessage", botToken, JSONObject().put("channel", chatId).put("text", text).apply { threadId?.let { put("thread_ts", it) } })
        Unit
    }
}
