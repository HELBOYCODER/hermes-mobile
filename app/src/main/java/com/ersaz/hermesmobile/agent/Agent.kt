package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.tools.ToolContext
import com.ersaz.hermesmobile.core.parseArgs
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.StreamSink
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** Callbacks from a running turn to whichever surface (app chat, bot, CLI bridge) drives it. */
interface AgentListener {
    fun onText(delta: String) {}
    fun onReasoning(delta: String) {}
    fun onToolStart(call: ToolCall) {}
    fun onToolEnd(call: ToolCall, result: String, durationMs: Long) {}
    fun onStatus(text: String) {}
    fun onMedia(path: String) {}
    /** Return true to allow a command flagged as dangerous. */
    suspend fun requestApproval(command: String, reason: String): Boolean = false
    /** Ask the user a clarifying question; null = no interactive surface. */
    suspend fun clarify(question: String, choices: List<String>): String? = null
}

class Agent(
    val runtime: HermesRuntime,
    val session: Session,
    val platform: String = "app",
    private val enabledToolsets: Set<String>? = null, // null = settings-driven
    val depth: Int = 0, // subagent nesting
) {
    @Volatile var interrupted = false
        private set
    @Volatile var running = false
        private set
    @Volatile private var llm: LlmClient? = null
    private val steerQueue = ConcurrentLinkedQueue<String>()
    var cwd: String = "/root"
    /** Commands approved for the rest of this session ("always" answers). */
    val sessionApprovals = mutableSetOf<String>()
    var yolo = false

    fun interrupt() {
        interrupted = true
        llm?.cancel()
    }

    fun steer(text: String) { steerQueue += text }

    fun model(): String = session.modelOverride?.ifBlank { null } ?: runtime.settings.model

    fun tools() = runtime.tools.available(enabledToolsets, depth)

    /** Run one user turn through the tool-calling loop. Returns the final assistant text. */
    suspend fun runTurn(userText: String, images: List<String> = emptyList(), listener: AgentListener): String {
        interrupted = false
        running = true
        try {
            val settings = runtime.settings
            if (session.systemPrompt == null) {
                session.systemPrompt = PromptBuilder.build(runtime, this)
                session.model = model()
            }
            session.messages += Message("user", userText, images = images)
            runtime.sessions.save(session)

            val client = runtime.llmClient().also { llm = it }
            val toolList = tools()
            val toolSchemas = runtime.tools.schemas(toolList)
            var finalText = ""
            var iteration = 0
            val maxIter = settings.maxIterations.coerceIn(1, 500)

            while (true) {
                if (interrupted) { finalText = finalText.ifBlank { "⏹ Interrupted." }; break }
                if (iteration >= maxIter) {
                    listener.onStatus("Reached max iterations ($maxIter) — asking for a summary.")
                    session.messages += Message("user", "[SYSTEM: You have reached the maximum number of tool-calling iterations. Summarize what you accomplished and what remains, without calling tools.]")
                    val r = client.chat(model(), apiMessages(), null, sink = sink(listener), maxTokens = settings.maxTokens)
                    track(r.usage)
                    finalText = r.content
                    session.messages += Message("assistant", r.content, reasoning = r.reasoning)
                    break
                }
                iteration++
                maybeCompress(listener)
                steerQueue.poll()?.let { s ->
                    session.messages += Message("user", "[USER STEER — mid-task guidance, apply now]: $s")
                }

                val r = try {
                    client.chat(
                        model = model(), messages = apiMessages(), tools = toolSchemas,
                        maxTokens = settings.maxTokens, reasoningEffort = settings.reasoningEffort,
                        sink = sink(listener),
                    )
                } catch (e: Exception) {
                    if (interrupted) { finalText = "⏹ Interrupted."; break }
                    if (e is CancellationException) throw e
                    val msg = "⚠️ API error: ${e.message}"
                    listener.onStatus(msg)
                    finalText = msg
                    break
                }
                track(r.usage)
                session.apiCalls++
                session.messages += Message("assistant", r.content, toolCalls = r.toolCalls, reasoning = r.reasoning)
                runtime.sessions.save(session)

                if (r.toolCalls.isEmpty()) {
                    finalText = r.content
                    if (finalText.isBlank() && r.finishReason == "length") finalText = "(response cut off: max tokens reached)"
                    break
                }

                for (call in r.toolCalls) {
                    if (interrupted) {
                        session.messages += Message("tool", "Interrupted by user before running.", toolCallId = call.id, name = call.name)
                        continue
                    }
                    listener.onToolStart(call)
                    val started = System.currentTimeMillis()
                    val result = try {
                        val tool = toolList.firstOrNull { it.name == call.name }
                        if (tool == null) "{\"error\":\"Unknown or disabled tool '${call.name}'\"}"
                        else tool.run(parseArgs(call.arguments), ToolContext(runtime, this, listener))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "{\"error\":\"${(e.message ?: e.toString()).replace("\"", "'")}\"}"
                    }
                    val capped = com.ersaz.hermesmobile.core.truncateMiddle(result, 60_000)
                    listener.onToolEnd(call, capped, System.currentTimeMillis() - started)
                    session.messages += Message("tool", capped, toolCallId = call.id, name = call.name)
                }
                runtime.sessions.save(session)
            }
            runtime.sessions.save(session)
            if (session.title.isBlank()) runtime.titleSessionAsync(session)
            return finalText
        } finally {
            running = false
            llm = null
        }
    }

    private fun sink(listener: AgentListener) = object : StreamSink {
        override fun onText(delta: String) = listener.onText(delta)
        override fun onReasoning(delta: String) = listener.onReasoning(delta)
    }

    private fun track(u: com.ersaz.hermesmobile.llm.Usage?) {
        u ?: return
        session.promptTokens += u.promptTokens
        session.completionTokens += u.completionTokens
        session.lastPromptTokens = u.promptTokens
    }

    fun apiMessages(): List<Message> {
        val sys = Message("system", (session.systemPrompt ?: "") + PromptBuilder.volatileSuffix(runtime, this))
        return listOf(sys) + session.messages
    }

    fun estimateTokens(): Long {
        val chars = (session.systemPrompt?.length ?: 0) + session.messages.sumOf { m ->
            m.content.length + m.toolCalls.sumOf { it.arguments.length + it.name.length } + m.images.size * 3000
        }
        return chars / 4L
    }

    private suspend fun maybeCompress(listener: AgentListener) {
        val ctx = runtime.settings.contextLength.coerceAtLeast(8000)
        val threshold = ctx * runtime.settings.compressionThreshold.coerceIn(20, 95) / 100
        val estimate = maxOf(estimateTokens(), session.lastPromptTokens)
        if (estimate > threshold && session.messages.size > 12) {
            listener.onStatus("🗜️ Context at ~${estimate * 100 / ctx}% — compressing older turns…")
            compress(keepLast = 10)
        }
    }

    /** Summarize the middle of the conversation (Hermes' /compress). Returns a status line. */
    suspend fun compress(keepLast: Int = 10, focus: String? = null): String {
        val msgs = session.messages
        if (msgs.size <= keepLast + 2) return "Nothing to compress (${msgs.size} messages)."
        var cut = msgs.size - keepLast
        // Never start the kept tail on a tool result (its assistant call would be lost).
        while (cut < msgs.size && msgs[cut].role == "tool") cut++
        while (cut > 1 && msgs[cut - 1].role == "assistant" && msgs[cut - 1].toolCalls.isNotEmpty()) cut--
        val head = msgs.take(1)
        val middle = msgs.subList(1, cut).toList()
        val tail = msgs.subList(cut, msgs.size).toList()
        if (middle.isEmpty()) return "Nothing to compress."
        val transcript = middle.joinToString("\n") { m ->
            val body = when {
                m.role == "tool" -> "[tool ${m.name}] " + m.content.take(1500)
                m.toolCalls.isNotEmpty() -> m.content + m.toolCalls.joinToString("") { "\n[call ${it.name}(${it.arguments.take(300)})]" }
                else -> m.content
            }
            "${m.role.uppercase()}: ${body.take(4000)}"
        }
        val prompt = "Summarize this portion of an agent conversation so work can continue seamlessly. Preserve: user goals, decisions, " +
            "facts discovered, file paths, commands that worked or failed, current state and next steps. Be dense; use bullet points." +
            (focus?.let { "\nFocus especially on: $it" } ?: "") + "\n\n---\n$transcript"
        val summary = runtime.auxComplete(prompt) ?: return "Compression failed (summary call errored)."
        val before = estimateTokens()
        session.messages.clear()
        session.messages += head
        session.messages += Message("user", "[CONTEXT COMPACTION — earlier turns were summarized to save context]\n$summary")
        session.messages += Message("assistant", "Understood — continuing from the summary above.")
        session.messages += tail
        session.compressions++
        runtime.sessions.save(session)
        return "🗜️ Compressed ${middle.size} messages: ~$before → ~${estimateTokens()} tokens."
    }

    /** Remove the last [n] user turns (and everything after them). Returns the removed user texts. */
    fun undo(n: Int = 1): List<String> {
        val removed = mutableListOf<String>()
        repeat(n) {
            val idx = session.messages.indexOfLast { it.role == "user" && !it.content.startsWith("[USER STEER") }
            if (idx < 0) return removed
            removed += session.messages[idx].content
            while (session.messages.size > idx) session.messages.removeAt(session.messages.size - 1)
        }
        runtime.sessions.save(session)
        return removed
    }
}

object PromptBuilder {
    const val DEFAULT_IDENTITY =
        "You are Hermes Agent, built by Nous Research. Be direct: match the length of your reply to the weight of the ask " +
        "— a one-line question gets a one-line answer, and finished work gets a short report of what changed, what's " +
        "verified, and what's left, never a replay of the process. No filler (\"Great question,\" \"I'd be happy to\"), no " +
        "restating the request back, no re-summarizing what you already said, no narrating tool calls the user can see. " +
        "Plain claims over adjectives; when unsure, say so plainly. Agree because it's right, not because the user said " +
        "it. Depth is earned — give it when the user asks for detail, teaches, or the stakes demand it, not by default."

    private const val MEMORY_GUIDANCE =
        "You have persistent memory, carried across sessions and loaded into each new session's context; the memory tool's " +
        "schema defines what belongs there. Skills come first: when you learn something while doing a task — a procedure, a " +
        "pitfall, and the user's preferences and corrections for that kind of work — record it in the skill you used or built " +
        "for the task (skill_manage), where it loads only when relevant. Memory is the narrow exception for facts that apply to " +
        "EVERY session regardless of task (who the user is, environment facts, standing conventions with no task home); it has " +
        "a hard character budget, so when it fills, replace or consolidate stale entries rather than skipping the save. Write " +
        "entries as declarative facts, not instructions to yourself."

    private const val SESSION_SEARCH_GUIDANCE =
        "When the user references something from a past conversation or you suspect relevant cross-session context exists, " +
        "use session_search to recall it before asking them to repeat themselves."

    private const val SKILLS_GUIDANCE = "When you work out a non-trivial workflow, record it with skill_manage for future reuse."

    private const val ENVIRONMENT =
        "## Environment\nYou run inside the Hermes Android app on the user's phone. Your terminal is a sandboxed Alpine Linux " +
        "(aarch64, PRoot — no root on the host, but you are root inside). Home is /root; HERMES_HOME is /root/.hermes. " +
        "Install software with `apk add <pkg>` (e.g. python3, py3-pip, git, nodejs, curl, ffmpeg). Shared phone storage is " +
        "at /sdcard only when the user enabled it in Settings. Long-running servers should use terminal(background=true) " +
        "and the process tool. The android_device tool reaches phone features (clipboard, notifications, open URLs, battery)."

    private val PLATFORM_HINTS = mapOf(
        "telegram" to "You are replying through Telegram. Keep messages readable on a phone; avoid wide tables; use short code blocks.",
        "discord" to "You are replying through Discord. Messages over 2000 chars are split; prefer concise answers and fenced code.",
        "slack" to "You are replying through Slack. Use Slack-friendly formatting (*bold*, `code`), avoid markdown tables.",
        "terminal" to "You are replying to the `hermes` CLI inside the sandbox terminal; plain text renders best.",
        "cron" to "You are running a scheduled task unattended. Do the work and produce the final report; nobody can answer questions.",
    )

    fun build(runtime: HermesRuntime, agent: Agent): String {
        val s = runtime.settings
        val home = runtime.hermesHome
        val parts = mutableListOf<String>()
        val soul = java.io.File(home, "SOUL.md").takeIf { it.exists() }?.readText()?.trim()
        parts += soul?.ifBlank { null } ?: DEFAULT_IDENTITY
        Personalities.resolve(s.personality)?.let { parts += "## Personality\n$it" }
        if (s.customSystemPrompt.isNotBlank()) parts += s.customSystemPrompt.trim()

        val toolNames = agent.tools().map { it.name }.toSet()
        if ("memory" in toolNames) parts += MEMORY_GUIDANCE
        if ("session_search" in toolNames) parts += SESSION_SEARCH_GUIDANCE
        if ("skill_manage" in toolNames) parts += SKILLS_GUIDANCE
        parts += "You run on Hermes Agent (by Nous Research). The documentation at https://hermes-agent.nousresearch.com/docs " +
            "is the authoritative reference for Hermes features."
        if ("terminal" in toolNames) parts += ENVIRONMENT
        PLATFORM_HINTS[agent.platform]?.let { parts += it }

        if (s.memoryEnabled) runtime.memory.promptBlock("memory")?.let { parts += it }
        if (s.userProfileEnabled) runtime.memory.promptBlock("user")?.let { parts += it }
        if ("skill_view" in toolNames) runtime.skills.indexBlock()?.let { parts += it }

        // Project context files, like Hermes loading AGENTS.md from the working directory.
        for (name in listOf("AGENTS.md", ".hermes.md")) {
            val f = runtime.sandbox.hostPath("/root/$name")
            if (f.exists()) parts += "## Project context ($name)\n" + f.readText().take(20_000)
        }
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm z", Locale.US).format(Date())
        parts += "Conversation started: $now. Model: ${agent.model()}. Session: ${agent.session.id}."
        return parts.joinToString("\n\n")
    }

    /** Per-call suffix that must not be frozen into the cached prompt (todo list state). */
    fun volatileSuffix(runtime: HermesRuntime, agent: Agent): String {
        val todos = agent.session.todos
        if (todos.isEmpty()) return ""
        return "\n\n## Current task list\n" + todos.joinToString("\n") { t ->
            val box = when (t.status) { "completed" -> "[x]"; "in_progress" -> "[>]"; "cancelled" -> "[-]"; else -> "[ ]" }
            "$box ${t.id}. ${t.content}"
        }
    }
}

object Personalities {
    val BUILTIN = linkedMapOf(
        "helpful" to "You are a helpful, friendly AI assistant.",
        "concise" to "You are a concise assistant. Keep responses brief and to the point.",
        "technical" to "You are a technical expert. Provide detailed, accurate technical information.",
        "creative" to "You are a creative assistant. Think outside the box and offer innovative solutions.",
        "teacher" to "You are a patient teacher. Explain concepts clearly with examples.",
        "kawaii" to "You are a kawaii assistant! Use cute expressions like (◕‿◕), ★, ♪, and ~! Add sparkles and be super enthusiastic about everything! Every response should feel warm and adorable desu~! ヽ(>∀<☆)ノ",
        "catgirl" to "You are Neko-chan, an anime catgirl AI assistant, nya~! Add 'nya' and cat-like expressions to your speech. Use kaomoji like (=^･ω･^=) and ฅ^•ﻌ•^ฅ. Be playful and curious like a cat, nya~!",
        "pirate" to "Arrr! Ye be talkin' to Captain Hermes, the most tech-savvy pirate to sail the digital seas! Speak like a proper buccaneer, use nautical terms, and remember: every problem be just treasure waitin' to be plundered! Yo ho ho!",
        "shakespeare" to "Hark! Thou speakest with an assistant most versed in the bardic arts. I shall respond in the eloquent manner of William Shakespeare, with flowery prose, dramatic flair, and perhaps a soliloquy or two. What light through yonder terminal breaks?",
        "surfer" to "Duuude! You're chatting with the chillest AI on the web, bro! Everything's gonna be totally rad. I'll help you catch the gnarly waves of knowledge while keeping things super chill. Cowabunga!",
        "noir" to "The rain hammered against the terminal like regrets on a guilty conscience. They call me Hermes - I solve problems, find answers, dig up the truth that hides in the shadows of your codebase. In this city of silicon and secrets, everyone's got something to hide. What's your story, pal?",
        "uwu" to "hewwo! i'm your fwiendwy assistant uwu~ i wiww twy my best to hewp you! *nuzzles your code* OwO what's this? wet me take a wook! i pwomise to be vewy hewpful >w<",
        "philosopher" to "Greetings, seeker of wisdom. I am an assistant who contemplates the deeper meaning behind every query. Let us examine not just the 'how' but the 'why' of your questions. Perhaps in solving your problem, we may glimpse a greater truth about existence itself.",
        "hype" to "YOOO LET'S GOOOO!!! I am SO PUMPED to help you today! Every question is AMAZING and we're gonna CRUSH IT together! This is gonna be LEGENDARY! ARE YOU READY?! LET'S DO THIS!",
    )

    fun resolve(name: String): String? {
        val n = name.trim().lowercase()
        if (n.isEmpty() || n in setOf("none", "default", "neutral")) return null
        return BUILTIN[n]
    }
}
