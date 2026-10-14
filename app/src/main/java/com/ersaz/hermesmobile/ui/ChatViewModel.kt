package com.ersaz.hermesmobile.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.ToolCall
import com.ersaz.hermesmobile.commands.CommandHost
import com.ersaz.hermesmobile.commands.CommandResult
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.core.parseArgs
import com.ersaz.hermesmobile.stt.StreamText
import com.ersaz.hermesmobile.stt.VoiceInput
import com.ersaz.hermesmobile.stt.VoiceLang
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed class ChatItem {
    abstract val key: String

    class User(override val key: String, val text: String, val images: List<String>) : ChatItem()

    class Assistant(override val key: String, text: String = "", reasoning: String = "", streaming: Boolean = false) : ChatItem() {
        var text by mutableStateOf(text)
        var reasoning by mutableStateOf(reasoning)
        var streaming by mutableStateOf(streaming)
    }

    class Tool(override val key: String, val name: String, val emoji: String, val preview: String, val args: String) : ChatItem() {
        var result by mutableStateOf<String?>(null)
        var durationMs by mutableStateOf<Long?>(null)
    }

    class Info(override val key: String, val text: String, val isError: Boolean = false) : ChatItem()
    class Media(override val key: String, val path: String) : ChatItem()
}

class ApprovalRequest(val command: String, val reason: String, val result: CompletableDeferred<Pair<Boolean, Boolean>>)
class ClarifyRequest(val question: String, val choices: List<String>, val result: CompletableDeferred<String>)

/** The app chat surface: drives an [Agent] and mirrors its events into Compose state. */
class ChatViewModel(app: Application) : AndroidViewModel(app), CommandHost {
    override val runtime = HermesRuntime.get(app)
    override val platform = "app"
    override val chatKey: String? = null

    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private var _agent: Agent = Agent(runtime, latestAppSession())
    override val agent: Agent get() = _agent

    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var approval by mutableStateOf<ApprovalRequest?>(null); private set
    var clarify by mutableStateOf<ClarifyRequest?>(null); private set
    var sessionTitle by mutableStateOf(""); private set
    var contextPct by mutableStateOf(0); private set
    var input by mutableStateOf("")
    val pendingImages = mutableStateListOf<String>()
    val pendingFiles = mutableStateListOf<Attachment>()

    // --- voice dictation (Google online speech engine, ported from Sokhan) ---
    var voiceLang by mutableStateOf(VoiceLang.fromCode(runtime.settings.voiceLang)); private set
    var listening by mutableStateOf(false); private set
    /** True between "stop" and the transcript arriving. */
    var transcribing by mutableStateOf(false); private set
    private var sendWhenDone = false
    var voiceError by mutableStateOf<String?>(null)
    private var voiceBase = ""
    private var voice: VoiceInput? = null

    fun cycleVoiceLang() {
        if (listening) return
        voiceLang = voiceLang.next()
        runtime.settings.voiceLang = voiceLang.code
    }

    private fun joinWithBase(extra: String): String =
        if (extra.isBlank()) voiceBase else if (voiceBase.isBlank()) extra else voiceBase.trimEnd() + " " + extra

    /** Mic button: tap to start dictating into the message box, tap again to stop. */
    fun toggleVoice() {
        val v = voice
        if (listening && v != null) { v.stop(); return }
        if (transcribing) return
        voiceError = null
        voiceBase = input
        val lang = voiceLang
        val engine = VoiceInput(
            onText = { t: StreamText -> ui { input = joinWithBase(VoiceInput.preview(t, lang)) } },
            onProcessing = { on -> ui { transcribing = on; if (on) listening = false } },
            onDone = { final, err ->
                ui {
                    if (final.isNotBlank()) input = joinWithBase(final)
                    if (err != null) voiceError = err
                    listening = false
                    transcribing = false
                    voice = null
                    if (sendWhenDone) { sendWhenDone = false; if (final.isNotBlank() || input.isNotBlank()) send() }
                }
            },
        )
        voice = engine
        sendWhenDone = false
        listening = true
        engine.start(lang)
    }

    /** X next to the mic: throw the recording away. */
    fun cancelVoice() {
        sendWhenDone = false
        voice?.cancel()
        listening = false
        transcribing = false
        voice = null
        input = voiceBase
    }

    override fun onCleared() { voice?.cancel(); super.onCleared() }
    private var job: Job? = null
    private var seq = 0
    private fun k() = "i${seq++}"

    init { rebuild() }

    private fun latestAppSession(): Session {
        val latest = runtime.sessions.list().firstOrNull { it.source == "app" }
        return latest?.let { runtime.sessions.load(it.id) }?.takeIf { it.messages.isNotEmpty() } ?: Session(source = "app")
    }

    /** Re-derive the visible transcript from the persisted session. */
    fun rebuild() {
        items.clear()
        val toolItems = mutableMapOf<String, ChatItem.Tool>()
        for (m in agent.session.messages) {
            when (m.role) {
                "user" -> {
                    val shown = when {
                        m.content.startsWith("[SYSTEM: The user invoked") -> "⚡ " + m.content.substringAfter("\"").substringBefore("\"") + " skill"
                        m.content.startsWith("[CONTEXT COMPACTION") -> null.also { items += ChatItem.Info(k(), "🗜️ Earlier turns were compressed") }
                        m.content.startsWith("[USER STEER") -> "🧭 " + m.content.substringAfter("]: ")
                        m.content.startsWith("[SYSTEM:") -> null
                        else -> m.content
                    }
                    if (shown != null) items += ChatItem.User(k(), shown, m.images)
                }
                "assistant" -> {
                    if (m.content.isNotBlank() || !m.reasoning.isNullOrBlank()) items += ChatItem.Assistant(k(), m.content, m.reasoning.orEmpty())
                    m.toolCalls.forEach { c -> toolItem(c).also { toolItems[c.id] = it; items += it } }
                }
                "tool" -> toolItems[m.toolCallId]?.result = m.content
            }
        }
        refreshMeta()
    }

    private fun toolItem(c: ToolCall): ChatItem.Tool {
        val tool = runtime.tools.byName(c.name)
        return ChatItem.Tool(k(), c.name, tool?.emoji ?: "⚡", tool?.preview(parseArgs(c.arguments)).orEmpty(), c.arguments)
    }

    private fun refreshMeta() {
        sessionTitle = agent.session.displayTitle()
        val ctx = maxOf(agent.session.lastPromptTokens, agent.estimateTokens())
        contextPct = (ctx * 100 / runtime.settings.contextLength.coerceAtLeast(1)).toInt()
    }

    /** The picker changed provider/model: drop any per-session override and refresh the header. */
    fun modelChanged() { agent.session.modelOverride = null; refreshMeta(); modelRev++ }
    var modelRev by mutableStateOf(0); private set

    override fun isBusy() = busy

    override fun newSession(): Agent {
        stop()
        _agent = Agent(runtime, Session(source = "app"))
        ui { rebuild(); status = "" }
        return _agent
    }

    override fun resumeSession(session: Session): Agent {
        stop()
        _agent = Agent(runtime, session, if (session.source in setOf("telegram", "discord", "slack")) session.source else "app")
        ui { rebuild() }
        return _agent
    }

    fun openSession(id: String) { runtime.sessions.load(id)?.let { resumeSession(it) } }

    override fun stop() {
        agent.interrupt()
        approval?.result?.complete(false to false)
        clarify?.result?.complete("(user cancelled)")
    }

    override fun background(prompt: String) {
        runtime.scope.launch {
            val s = Session(source = "app", title = "bg: ${prompt.take(40)}")
            val r = try { Agent(runtime, s).runTurn(prompt, emptyList(), object : AgentListener {}) } catch (e: Exception) { "failed: ${e.message}" }
            runtime.notify("Hermes background task done", r)
            ui { items += ChatItem.Info(k(), "🚀 Background task finished (${s.id}): ${r.take(300)}") }
        }
    }

    fun answerApproval(ok: Boolean, always: Boolean = false) {
        approval?.result?.complete(ok to always); approval = null
    }

    fun answerClarify(text: String) {
        clarify?.result?.complete(text); clarify = null
    }

    /** Called from the input bar. */
    fun send() {
        val text = input.trim()
        val images = pendingImages.toList()
        val files = pendingFiles.toList()
        if (listening || transcribing) {
            // Send pressed while dictating: finish the recording, then send what was said.
            sendWhenDone = true
            voice?.stop()
            return
        }
        if (text.isEmpty() && images.isEmpty() && files.isEmpty()) return
        input = ""
        pendingImages.clear()
        pendingFiles.clear()
        viewModelScope.launch {
            if (images.isEmpty() && files.isEmpty() && text.startsWith("/")) {
                val beforeAgent = agent
                val beforeCount = agent.session.messages.size
                items += ChatItem.User(k(), text, emptyList())
                val r = Commands.handle(text, this@ChatViewModel)
                // /undo, /retry, /compress, /new, /resume rewrite history — re-derive the transcript.
                if (agent !== beforeAgent || agent.session.messages.size != beforeCount) {
                    rebuild()
                    if (r !is CommandResult.Prompt) items += ChatItem.User(k(), text, emptyList())
                }
                when (r) {
                    is CommandResult.Reply -> { items += ChatItem.Info(k(), r.text); refreshMeta() }
                    is CommandResult.Prompt -> {
                        // /retry resends the original message (show it); skills//plan etc. keep the typed command as the echo.
                        // (After /retry the transcript was rebuilt without the echo above.)
                        val isRetry = r.echo?.startsWith("↻") == true
                        runTurn(r.text, emptyList(), showUser = isRetry)
                    }
                    else -> {}
                }
                return@launch
            }
            if (busy) {
                // Hermes "busy" default: a new message interrupts the running turn.
                agent.interrupt(); job?.join()
            }
            val lead = text.ifEmpty { if (images.isNotEmpty()) "What's in this image?" else "Please look at the attached file(s)." }
            runTurn(lead + attachmentNote(files), images, showUser = true)
        }
    }

    private fun runTurn(text: String, images: List<String>, showUser: Boolean) {
        if (showUser) items += ChatItem.User(k(), text, images)
        busy = true
        status = ""
        val a = agent
        job = viewModelScope.launch {
            var current: ChatItem.Assistant? = null
            val calls = mutableMapOf<String, ChatItem.Tool>()
            fun assistant(): ChatItem.Assistant = current ?: ChatItem.Assistant(k(), streaming = true).also { current = it; items += it }
            val listener = object : AgentListener {
                override fun onText(delta: String) = ui { assistant().text += delta }
                override fun onReasoning(delta: String) = ui { if (runtime.settings.showReasoning) assistant().reasoning += delta }
                override fun onToolStart(call: ToolCall) = ui {
                    current?.streaming = false
                    current = null
                    if (runtime.settings.toolProgress != "off") toolItem(call).also { calls[call.id] = it; items += it }
                }
                override fun onToolEnd(call: ToolCall, result: String, durationMs: Long) = ui {
                    calls[call.id]?.let { it.result = result; it.durationMs = durationMs }
                }
                override fun onStatus(text: String) = ui { items += ChatItem.Info(k(), text) }
                override fun onMedia(path: String) = ui {
                    items += ChatItem.Media(k(), path)
                    runtime.speech.play(File(path))
                }
                override suspend fun requestApproval(command: String, reason: String): Boolean {
                    val d = CompletableDeferred<Pair<Boolean, Boolean>>()
                    ui { approval = ApprovalRequest(command, reason, d) }
                    val (ok, always) = d.await()
                    if (ok && always) a.sessionApprovals += command
                    return ok
                }
                override suspend fun clarify(question: String, choices: List<String>): String {
                    val d = CompletableDeferred<String>()
                    ui { clarify = ClarifyRequest(question, choices, d) }
                    return d.await()
                }
            }
            try {
                val result = a.runTurn(text, images, listener)
                ui {
                    val cur = current
                    if (cur != null) { cur.streaming = false; if (cur.text.isBlank() && result.isNotBlank()) cur.text = result }
                    else if (result.isNotBlank() && items.lastOrNull() !is ChatItem.Assistant) items += ChatItem.Assistant(k(), result)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ui { items += ChatItem.Info(k(), "⚠️ ${e.message}", isError = true) }
            } finally {
                ui { busy = false; current?.streaming = false; refreshMeta() }
            }
        }
    }

    fun deleteSession(id: String) {
        runtime.sessions.delete(id)
        if (id == agent.session.id) newSession()
    }
}
