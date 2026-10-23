package com.ersaz.hermesmobile.gateway

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.core.Settings
import com.ersaz.hermesmobile.core.objects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class IncomingMessage(
    val platform: String,
    val chatId: String,
    val userId: String,
    val userName: String,
    val text: String,
    val images: List<String> = emptyList(),
    val isDm: Boolean,
    val threadId: String? = null,
    /**
     * The platform's unique, owner-assignable handle (Telegram @username, Discord username), or null.
     * Never a display name: [userName] may fall back to a free-form first name anyone can set.
     */
    val handle: String? = null,
)

data class PairingRequest(val platform: String, val userId: String, val userName: String, val code: String, val at: Long)

/** Hermes' messaging gateway: one agent, many platforms, per-chat sessions, DM pairing. */
class GatewayManager(private val rt: HermesRuntime) {
    companion object {
        /**
         * Allow-list check: "*" opens the gateway, otherwise the stable user id or the unique handle must be
         * listed. Display names are deliberately not matched: a Telegram user without a username used to be
         * checked by first name, which anyone can set to an allowed name to get in.
         */
        fun isAllowed(allowed: List<String>, userId: String, handle: String?): Boolean {
            if ("*" in allowed) return true
            if (userId.isNotBlank() && userId in allowed) return true
            val h = handle?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() } ?: return false
            return allowed.any { it.removePrefix("@").equals(h, ignoreCase = true) }
        }
    }

    private val dir = File(rt.hermesHome, "gateway").apply { mkdirs() }
    private val sessionsFile = File(dir, "sessions.json")
    private val pairedFile = File(dir, "paired.json")
    private val runners = ConcurrentHashMap<String, ChatRunner>()
    val adapters = ConcurrentHashMap<String, PlatformAdapter>()

    private val _pending = MutableStateFlow<List<PairingRequest>>(emptyList())
    val pending: StateFlow<List<PairingRequest>> = _pending
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.update { (it + "$ts  $line").takeLast(200) }
    }

    // ----------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        stopAdapters()
        val s = rt.settings
        if (s.telegramToken.isNotBlank()) adapters["telegram"] = TelegramAdapter(rt, this, s.telegramToken)
        if (s.discordToken.isNotBlank()) adapters["discord"] = DiscordAdapter(rt, this, s.discordToken)
        if (s.slackBotToken.isNotBlank() && s.slackAppToken.isNotBlank()) adapters["slack"] = SlackAdapter(rt, this, s.slackBotToken, s.slackAppToken)
        adapters.values.forEach { it.start() }
        _running.value = adapters.isNotEmpty()
        log(if (adapters.isEmpty()) "No platforms configured — add a bot token." else "Gateway started: ${adapters.keys.joinToString()}")
    }

    @Synchronized
    fun stop() {
        stopAdapters()
        _running.value = false
        log("Gateway stopped")
    }

    private fun stopAdapters() {
        adapters.values.forEach { runCatching { it.stop() } }
        adapters.clear()
    }

    // ----------------------------------------------------------- inbound

    fun onIncoming(msg: IncomingMessage) {
        val adapter = adapters[msg.platform] ?: return
        rt.scope.launch {
            if (!authorize(msg, adapter)) return@launch
            val key = "${msg.platform}:${msg.chatId}" + (msg.threadId?.let { ":$it" } ?: "")
            val out = adapter.outputFor(msg.chatId, msg.threadId)
            val runner = runners.getOrPut(key) { ChatRunner(rt, msg.platform, key, out) }
            runner.out = out
            log("${msg.platform} ← ${msg.userName}: ${msg.text.take(60)}")
            try { runner.onMessage(msg.text, msg.images) } catch (e: Exception) { log("error: ${e.message}") }
        }
    }

    private suspend fun authorize(msg: IncomingMessage, adapter: PlatformAdapter): Boolean {
        val allowed = when (msg.platform) {
            "telegram" -> rt.settings.telegramAllowed
            "discord" -> rt.settings.discordAllowed
            "slack" -> rt.settings.slackAllowed
            else -> ""
        }.let { Settings.splitList(it) }
        if (isAllowed(allowed, msg.userId, msg.handle)) return true
        if (isPaired(msg.platform, msg.userId)) return true

        // Unknown user → DM pairing code the owner approves in the app (hermes pairing approve).
        if (!msg.isDm) return false
        val existing = _pending.value.firstOrNull { it.platform == msg.platform && it.userId == msg.userId }
        val code = existing?.code ?: (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        if (existing == null) {
            _pending.update { list -> list + PairingRequest(msg.platform, msg.userId, msg.userName, code, System.currentTimeMillis()) }
            rt.notify("Hermes pairing request", "${msg.userName} on ${msg.platform} wants access. Code $code — approve in Messaging gateway.")
            log("pairing request from ${msg.userName} (${msg.userId}) code $code")
        }
        adapter.outputFor(msg.chatId, null).send(
            "☤ Hi! This Hermes agent is private.\nYour pairing code: $code\nAsk the owner to approve it in the Hermes app → Messaging gateway."
        )
        return false
    }

    // ----------------------------------------------------------- pairing store

    @Synchronized
    private fun pairedJson(): JSONObject = try { JSONObject(pairedFile.readText()) } catch (_: Exception) { JSONObject() }

    fun isPaired(platform: String, userId: String) =
        pairedJson().optJSONArray(platform)?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("id") == userId } } == true

    fun pairedUsers(): List<Triple<String, String, String>> {
        val o = pairedJson()
        return o.keys().asSequence().flatMap { p ->
            (o.optJSONArray(p) ?: JSONArray()).objects().map { Triple(p, it.optString("id"), it.optString("name")) }
        }.toList()
    }

    @Synchronized
    fun approvePairing(req: PairingRequest) {
        val o = pairedJson()
        val arr = o.optJSONArray(req.platform) ?: JSONArray()
        arr.put(JSONObject().put("id", req.userId).put("name", req.userName).put("at", System.currentTimeMillis()))
        o.put(req.platform, arr)
        pairedFile.writeText(o.toString(2))
        _pending.update { list -> list.filterNot { it == req } }
        log("approved ${req.userName} on ${req.platform}")
    }

    fun rejectPairing(req: PairingRequest) { _pending.update { list -> list.filterNot { it == req } } }

    @Synchronized
    fun revoke(platform: String, userId: String) {
        val o = pairedJson()
        val arr = o.optJSONArray(platform) ?: return
        o.put(platform, JSONArray(arr.objects().filterNot { it.optString("id") == userId }))
        pairedFile.writeText(o.toString(2))
    }

    // ----------------------------------------------------------- chat ↔ session binding

    @Synchronized
    fun sessionIdFor(chatKey: String): String? =
        try { JSONObject(sessionsFile.readText()).optString(chatKey).ifBlank { null } } catch (_: Exception) { null }

    @Synchronized
    fun bindSession(chatKey: String, sessionId: String) {
        val o = try { JSONObject(sessionsFile.readText()) } catch (_: Exception) { JSONObject() }
        o.put(chatKey, sessionId)
        sessionsFile.writeText(o.toString(2))
    }

    /** Runner for the in-sandbox `hermes` CLI (shares the same machinery as bots). */
    fun localRunner(key: String, out: ChatOutput): ChatRunner =
        runners.getOrPut("terminal:$key") { ChatRunner(rt, "terminal", "terminal:$key", out) }.also { it.out = out }

    // ----------------------------------------------------------- home channels / outbound

    fun homes(): JSONObject = try { JSONObject(rt.settings.homeChannels) } catch (_: Exception) { JSONObject() }

    fun setHome(platform: String, chatId: String) {
        rt.settings.homeChannels = homes().put(platform, chatId).toString()
    }

    fun describeTargets(): String {
        val h = homes()
        return listOf("telegram", "discord", "slack").joinToString("\n") { p ->
            "$p: ${if (adapters.containsKey(p)) "connected" else "offline"}, home=${h.optString(p).ifBlank { "(unset — use /sethome)" }}"
        }
    }

    /** Send to "platform" (home channel), "platform:chatId" or "platform:chatId:thread". */
    suspend fun sendTo(target: String, text: String): Result<String> {
        val platform = target.substringBefore(':').lowercase()
        val adapter = adapters[platform] ?: return Result.failure(IllegalStateException("$platform is not connected (enable the messaging gateway and add its token)"))
        val rest = target.substringAfter(':', "")
        val chatId = rest.substringBefore(':').ifBlank { homes().optString(platform) }
        if (chatId.isBlank()) return Result.failure(IllegalStateException("No home channel for $platform — send /sethome in the chat first"))
        val thread = rest.substringAfter(':', "").ifBlank { null }
        return try {
            adapter.outputFor(chatId, thread).send(text)
            Result.success("$platform:$chatId")
        } catch (e: Exception) { Result.failure(e) }
    }

    fun statusText(): String {
        val s = rt.settings
        fun line(p: String, configured: Boolean) =
            "${if (adapters[p]?.connected?.value == true) "🟢" else if (configured) "🟡" else "⚪"} $p — " +
                (adapters[p]?.status?.value ?: if (configured) "configured, gateway stopped" else "not configured")
        return "📡 Platforms\n" + listOf(
            line("telegram", s.telegramToken.isNotBlank()),
            line("discord", s.discordToken.isNotBlank()),
            line("slack", s.slackBotToken.isNotBlank()),
        ).joinToString("\n") + "\n\nHome channels: " + homes().toString()
    }
}
