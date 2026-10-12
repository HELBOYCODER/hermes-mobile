package com.ersaz.hermesmobile.gateway

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.ToolCall
import com.ersaz.hermesmobile.commands.CommandHost
import com.ersaz.hermesmobile.commands.CommandResult
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.core.parseArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Where a chat's replies go (a Telegram chat, a Discord channel, an HTTP response…). */
interface ChatOutput {
    suspend fun send(text: String)
    suspend fun typing() {}
    suspend fun sendFile(file: File, caption: String? = null) { send("📎 ${file.name}") }
    /** Raw streaming text (CLI bridge); messaging platforms leave this unimplemented. */
    fun stream(delta: String) {}
    val streams: Boolean get() = false
}

/**
 * One conversation on one surface — the gateway's per-chat session runner.
 * Busy policy follows Hermes' default: a new message interrupts the running turn.
 */
class ChatRunner(
    override val runtime: HermesRuntime,
    override val platform: String,
    override val chatKey: String,
    var out: ChatOutput,
) : CommandHost {

    private var _agent: Agent = loadAgent()
    override val agent: Agent get() = _agent
    private var job: Job? = null
    @Volatile private var pendingApproval: CompletableDeferred<Pair<Boolean, Boolean>>? = null
    @Volatile private var pendingClarify: CompletableDeferred<String>? = null

    private fun loadAgent(): Agent {
        val sid = runtime.gateway.sessionIdFor(chatKey)
        val session = sid?.let { runtime.sessions.load(it) }
            ?: Session(source = platform, chatKey = chatKey).also {
                runtime.sessions.save(it); runtime.gateway.bindSession(chatKey, it.id)
            }
        return Agent(runtime, session, platform)
    }

    override fun isBusy() = job?.isActive == true

    override fun newSession(): Agent {
        stop()
        val s = Session(source = platform, chatKey = chatKey)
        runtime.sessions.save(s)
        runtime.gateway.bindSession(chatKey, s.id)
        _agent = Agent(runtime, s, platform)
        return _agent
    }

    override fun resumeSession(session: Session): Agent {
        stop()
        runtime.gateway.bindSession(chatKey, session.id)
        _agent = Agent(runtime, session, platform)
        return _agent
    }

    override fun stop() {
        _agent.interrupt()
        pendingApproval?.complete(false to false)
        pendingClarify?.complete("(user stopped the task)")
        job?.cancel()
    }

    override fun approve(always: Boolean): Boolean {
        val p = pendingApproval ?: return false
        p.complete(true to always); return true
    }

    override fun deny(): Boolean {
        val p = pendingApproval ?: return false
        p.complete(false to false); return true
    }

    override fun setHome(): String? {
        if (platform !in setOf("telegram", "discord", "slack")) return null
        runtime.gateway.setHome(platform, chatKey.substringAfter(':'))
        return chatKey
    }

    override fun background(prompt: String) {
        runtime.scope.launch {
            val s = Session(source = platform, chatKey = chatKey, title = "bg: ${prompt.take(40)}")
            val result = try { Agent(runtime, s, platform).runTurn(prompt, emptyList(), object : AgentListener {}) } catch (e: Exception) { "failed: ${e.message}" }
            out.send("🚀 Background task finished: ${prompt.take(60)}\n\n$result")
        }
    }

    /** Entry point for every inbound message on this chat. */
    suspend fun onMessage(text: String, images: List<String> = emptyList()) {
        val trimmed = text.trim()
        pendingClarify?.let { if (!trimmed.startsWith("/") && trimmed.isNotEmpty()) { it.complete(trimmed); return } }
        if (pendingApproval != null && trimmed.lowercase() in setOf("yes", "y", "approve", "ok", "بله")) { approve(false); return }
        if (pendingApproval != null && trimmed.lowercase() in setOf("no", "n", "deny", "نه")) { deny(); return }

        when (val r = if (images.isEmpty()) Commands.handle(trimmed, this) else null) {
            is CommandResult.Reply -> out.send(r.text)
            is CommandResult.Prompt -> startTurn(r.text, emptyList())
            CommandResult.Silent -> {}
            null -> if (trimmed.isNotEmpty() || images.isNotEmpty()) startTurn(trimmed.ifEmpty { "What's in this image?" }, images)
        }
    }

    /** Run a turn and suspend until it finishes (the CLI bridge needs to know when to close the response). */
    suspend fun runAndWait(text: String) {
        onMessage(text)
        job?.join()
    }

    private suspend fun startTurn(text: String, images: List<String>) {
        if (isBusy()) {
            _agent.interrupt()
            job?.join()
        }
        val agent = _agent
        job = runtime.scope.launch {
            val typing = launch { while (isActive) { runCatching { out.typing() }; delay(4500) } }
            val progressMode = runtime.settings.gatewayToolProgress
            var lastTool = ""
            val media = mutableListOf<String>()
            val listener = object : AgentListener {
                override fun onText(delta: String) { if (out.streams) out.stream(delta) }
                override fun onToolStart(call: ToolCall) {
                    val tool = runtime.tools.byName(call.name)
                    val emoji = tool?.emoji ?: "⚡"
                    val line = when (progressMode) {
                        "off" -> null
                        "new" -> if (call.name != lastTool) "$emoji ${call.name}" else null
                        "verbose" -> "$emoji ${call.name}: ${call.arguments.take(400)}"
                        else -> "$emoji ${call.name}" + (tool?.preview(parseArgs(call.arguments))?.let { if (it.isNotBlank()) ": $it" else "" } ?: "")
                    }
                    lastTool = call.name
                    if (line != null) {
                        if (out.streams) out.stream("\n┊ $line\n") else runtime.scope.launch { runCatching { out.send(line) } }
                    }
                }
                override fun onStatus(text: String) {
                    if (out.streams) out.stream("\n$text\n") else if (progressMode != "off") runtime.scope.launch { runCatching { out.send(text) } }
                }
                override fun onMedia(path: String) { media += path }
                override suspend fun requestApproval(command: String, reason: String): Boolean {
                    val d = CompletableDeferred<Pair<Boolean, Boolean>>()
                    pendingApproval = d
                    out.send("⚠️ Dangerous command needs approval ($reason):\n\n$command\n\nReply /approve, /approve always, or /deny" +
                        if (platform == "terminal") "  (from another terminal: hermes /approve)" else "")
                    val (ok, always) = withTimeoutOrNull(300_000) { d.await() } ?: (false to false)
                    pendingApproval = null
                    if (ok && always) agent.sessionApprovals += command
                    return ok
                }
                override suspend fun clarify(question: String, choices: List<String>): String? {
                    val d = CompletableDeferred<String>()
                    pendingClarify = d
                    out.send("❓ $question" + if (choices.isNotEmpty()) "\n" + choices.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n") else "")
                    val answer = withTimeoutOrNull(600_000) { d.await() }
                    pendingClarify = null
                    return answer?.let { a -> a.toIntOrNull()?.let { choices.getOrNull(it - 1) } ?: a }
                }
            }
            try {
                val result = agent.runTurn(text, images, listener)
                typing.cancel()
                val (clean, mediaPaths) = extractMedia(result)
                if (!out.streams && clean.isNotBlank()) out.send(clean)
                (media + mediaPaths).distinct().forEach { p -> File(p).takeIf { it.exists() }?.let { out.sendFile(it) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                out.send("⚠️ ${e.message ?: e.toString()}")
            } finally {
                typing.cancel()
            }
        }
    }

    /** Hermes convention: `MEDIA:/path` in a reply attaches that file on messaging platforms. */
    private fun extractMedia(text: String): Pair<String, List<String>> {
        val rx = Regex("MEDIA:(\\S+)")
        val paths = rx.findAll(text).map { m ->
            val p = m.groupValues[1]
            if (p.startsWith(runtime.sandbox.rootfs.absolutePath)) p else runtime.sandbox.hostPath(p).absolutePath
        }.toList()
        return rx.replace(text, "").trim() to paths
    }
}
