package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.bots.Bot
import com.ersaz.hermesmobile.agent.tools.ToolContext
import com.ersaz.hermesmobile.core.parseArgs
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.StreamSink
import kotlinx.coroutines.CancellationException
import java.io.File
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
    fun onError(text: String) { onStatus(text) }
    fun onMedia(path: String) {}
    fun onFile(path: String) { onMedia(path) }
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
    /** Bot Mode profile this agent runs as; null = the default Hermes. */
    val bot: Bot? = null,
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
    /** Bots already involved in this bot-to-bot chain (loop guard for message_bot). */
    var botChain: List<String> = emptyList()

    val botId: String get() = bot?.id ?: Bot.DEFAULT_ID
    /** The memory this agent reads and writes: its own for a bot, the shared one for default Hermes. */
    val memory get() = runtime.bots.memoryFor(bot)

    fun interrupt() {
        interrupted = true
        llm?.cancel()
    }

    fun steer(text: String) { steerQueue += text }

    fun model(): String = session.modelOverride?.ifBlank { null }
        ?: bot?.model?.ifBlank { null } ?: runtime.settings.model

    fun tools() = runtime.tools.available(enabledToolsets, depth, bot?.disabledToolsets.orEmpty().toSet())
        .filter { it.name != "message_bot" || runtime.bots.all().size > 1 } // nobody to message in a one-bot roster

    /** Run one user turn through the tool-calling loop. Returns the final assistant text. */
    suspend fun runTurn(userText: String, images: List<String> = emptyList(), listener: AgentListener, retryMessageId: String? = null): String {
        interrupted = false
        running = true
        if (depth == 0) runtime.botHub.setRunning(botId, true)
        val userMessage = retryMessageId?.let { id -> session.messages.firstOrNull { it.localId == id && it.role == "user" } }
            ?: Message("user", userText, images = images)
        fun markFailure(text: String) {
            val index = session.messages.indexOfFirst { it.localId == userMessage.localId }
            if (index >= 0) session.messages[index] = session.messages[index].copy(failureReason = text)
            runtime.sessions.save(session)
        }
        val downstream = listener
        val delivered = userMessage.generatedFiles.toMutableSet()
        val listener = object : AgentListener by downstream {
            private fun record(path: String): Boolean {
                val storedPath = runtime.sandbox.guestPath(File(path))
                if (!delivered.add(storedPath)) return false
                val index = session.messages.indexOfFirst { it.localId == userMessage.localId }
                if (index >= 0) session.messages[index] = session.messages[index].copy(generatedFiles = delivered.toList())
                runtime.sessions.save(session)
                return true
            }
            override fun onFile(path: String) { if (record(path)) downstream.onFile(path) }
            override fun onMedia(path: String) {
                if (runtime.generatedFiles.isDelivered(File(path))) { if (record(path)) downstream.onMedia(path) }
                else downstream.onMedia(path)
            }
        }
        val outputs = runtime.sandbox.hostPath("/root/outputs").apply { mkdirs() }
        suspend fun outputState() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            outputs.walkTopDown().maxDepth(5).filter { it.isFile && !it.name.startsWith(".") && it.extension !in setOf("tmp", "part") }
                .take(200).associate { it.canonicalPath to (it.lastModified() to it.length()) }
        }
        var knownOutputs = outputState()
        suspend fun deliverOutputs() {
            val current = outputState()
            current.filter { (path, stamp) -> knownOutputs[path] != stamp }.keys.forEach { path ->
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.generatedFiles.capture(File(path)) } }
                    .onSuccess { listener.onFile(it.absolutePath) }
                    .onFailure { listener.onStatus("Could not attach ${File(path).name}: ${it.message}") }
            }
            knownOutputs = current
        }
        try {
            val settings = runtime.settings
            if (retryMessageId == null) session.messages += userMessage
            runtime.sessions.save(session)
            if (session.systemPrompt == null) {
                session.systemPrompt = PromptBuilder.build(runtime, this)
                session.model = model()
            }
            runtime.sessions.save(session)

            val client = runtime.llmClient().also { llm = it }
            runtime.mcp.awaitReady() // MCP servers may still be connecting right after launch
            val toolList = tools()
            if (retryMessageId == null && com.ersaz.hermesmobile.agent.tools.AnalyzeLinkTool.isBareMediaLink(userText.trim())) {
                toolList.firstOrNull { it.name == "analyze_link" }?.let { media ->
                    val call = ToolCall(java.util.UUID.randomUUID().toString(), media.name, org.json.JSONObject().put("url", userText.trim()).toString())
                    session.messages += Message("assistant", toolCalls = listOf(call))
                    listener.onToolStart(call)
                    val started = System.currentTimeMillis()
                    val evidence = try { media.run(org.json.JSONObject(call.arguments), ToolContext(runtime, this, listener)) }
                    catch (e: CancellationException) {
                        session.messages += Message("tool", "Media extraction cancelled. No complete evidence obtained.", toolCallId = call.id, name = call.name)
                        runtime.sessions.save(session)
                        throw e
                    }
                    catch (e: Exception) { "{\"error\":\"Media extraction unavailable\",\"coverage\":\"none\"}" }
                    session.messages += Message("tool", evidence, toolCallId = call.id, name = call.name)
                    listener.onToolEnd(call, evidence, System.currentTimeMillis() - started)
                    runtime.sessions.save(session)
                }
            }
            val toolSchemas = runtime.tools.schemas(toolList)
            var finalText = ""
            var iteration = 0
            val maxIter = settings.maxIterations.coerceIn(1, 500)
            val loopDetector = ToolLoopDetector()
            var loopStop = false

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
                    val msg = runtime.settings.redactEnvironment("⚠️ API error: ${e.message}")
                    markFailure(msg)
                    listener.onError(msg)
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
                    var capped = com.ersaz.hermesmobile.core.truncateMiddle(runtime.settings.redactEnvironment(result), 60_000)
                    when (loopDetector.record(call.name, call.arguments, capped)) {
                        ToolLoopDetector.Verdict.WARN -> capped += loopDetector.nudge(call.name)
                        ToolLoopDetector.Verdict.STOP -> loopStop = true
                        else -> {}
                    }
                    // Token saver (ON by default): compress this tool
                    // result for MODEL CONTEXT only. Loop detection above
                    // ran on the uncompressed text; files the agent writes
                    // are never touched by TokenSaver.
                    val codeAware = call.name in setOf("read_file", "search_files", "read", "cat")
                    val forModel = if (runtime.settings.tokenSaver) TokenSaver.compressForContext(capped, codeAware) else capped
                    TokenSaver.record(capped.length, forModel.length)
                    listener.onToolEnd(call, forModel, System.currentTimeMillis() - started)
                    session.messages += Message("tool", forModel, toolCallId = call.id, name = call.name)
                }
                deliverOutputs()
                runtime.sessions.save(session)
                if (loopStop) {
                    finalText = "⚠️ Stopped: I kept repeating the same `${r.toolCalls.last().name}` call with the same result. Tell me how you'd like to proceed."
                    session.messages += Message("assistant", finalText)
                    break
                }
            }
            val linkedFiles = Regex("""\[[^\]]*]\(([^)]+)\)""").findAll(finalText)
                .mapNotNull { com.ersaz.hermesmobile.files.WebLinks.localTarget(it.groupValues[1]) }.distinct().take(20)
            linkedFiles.forEach { guest ->
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runtime.generatedFiles.capture(runtime.sandbox.hostPath(guest, cwd))
                } }.onSuccess { listener.onFile(it.absolutePath) }
            }
            deliverOutputs()
            runtime.sessions.save(session)
            if (session.title.isBlank()) runtime.titleSessionAsync(session)
            return finalText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = runtime.settings.redactEnvironment("⚠️ ${e.message ?: "Request failed"}")
            markFailure(message)
            listener.onError(message)
            return message
        } finally {
            running = false
            llm = null
            if (depth == 0) runtime.botHub.setRunning(botId, false)
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
        runtime.usage.record(model(), u.promptTokens, u.completionTokens)
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
        if (!session.autoCompact) return
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

    /**
     * Hermes-owned design routing (not Huashu's text): when the user asks
     * for a design, the huashu-design skill (bundled by default, ON)
     * governs the flow. The skill's own SKILL.md is kept verbatim; this
     * note only routes to it and states this phone's delivery paths.
     * PPTX honesty rule: the skill's editable-PPTX paths need
     * html2pptx.js + pptxgenjs (Node) or python-pptx, neither of which
     * is bundled or verified in the sandbox — so HTML deck first, and
     * never claim a PPTX you did not actually produce.
     */
    private const val DESIGN_SKILL_ROUTING =
        "## Design requests (huashu-design skill, bundled by default, ON)\n" +
        "When the user asks for a design — a website, a clickable app prototype, an editable presentation, an infographic, " +
        "a product animation, or any new visual design — load the huashu-design skill with skill_view and follow its flow " +
        "exactly: generate 6 preview candidates first, let the user pick 3 directions, then polish the final output. " +
        "Deliver HTML outputs into the project folder, preview them in the existing Browser screen, ZIP-export via the " +
        "existing DownloadsExporter path, and use the Spec-first checklist for build tasks. HTML deck first: editable PPTX " +
        "is available only if a verified converter is present (it is not bundled); never claim a PPTX you did not produce, " +
        "and never claim MP4/GIF rendering you did not run."

    /**
     * REA mode (reverse-engineer-anything skill, bundled by default, ON).
     * Trigger: the user says "reverse engineer this" (or drops a binary /
     * APK / bundle into the workspace). The skill's own SKILL.md is kept
     * verbatim; this note only routes to it and enforces its core rule.
     * The desktop `npx rea-agents setup` is NOT used on Android — the
     * phone equivalent is the Coding "Reverse engineering" panel
     * (sandbox binutils/strings/readelf/objdump, APK manifest+dex
     * strings, JS analysis) plus this prompt mode. Local-only: nothing
     * analyzed is uploaded. Ghidra headless and Hopper are desktop-only
     * and are never claimed to run on-phone.
     */
    private const val REA_MODE_ROUTING =
        "## Reverse engineering (rea skill, bundled by default, ON)\\n" +
        "When the user says \"reverse engineer this\" (or drops a binary, APK, Electron bundle, JS bundle, or .NET assembly " +
        "into the workspace), switch into REA mode: load the reverse-engineer-anything skill with skill_view and follow its " +
        "phases exactly. Core rule, never broken: EVERY finding carries its evidence (tool output, offset, string, symbol) " +
        "AND its limitation/confidence — analysis is never presented as a guess, and unverified claims are labelled " +
        "hypotheses. Report findings grouped behavior → strings/symbols → code structure → data, each with evidence + " +
        "limitation + confidence, and end with an unknowns section for what could not be determined. Identify targets by " +
        "magic bytes first, extension second. On-phone tools only (sandbox file/strings/readelf/objdump/nm, APK manifest " +
        "and dex strings, JS analysis); full APK decompilation (apktool/jadx), Ghidra headless, and Hopper need a desktop " +
        "run — say which step ran on-phone and which needs desktop, never blur the two, and offer the exported analysis " +
        "package for desktop continuation. Local-only: never upload a binary, sample, or analysis output anywhere."

    /**
     * Full design flow (all ON by default, after the Huashu 6→3 step):
     * Huashu first (6 preview candidates → user picks 3 → polish), then
     * ui-ux-pro-max palettes/font pairs as choices, impeccable critique
     * pass before final delivery, GSAP for HTML animation, React Bits
     * for web prototypes only, HyperFrames HTML scenes (video only if
     * the sandbox toolchain is verified), Blender scripts only, and
     * Framelink Figma import. Honesty notes: the impeccable pass is
     * instruction-level (the agent runs it before delivery; it is not
     * a separate blocking validator process). React Bits components are
     * never native Android components. Blender has no live phone
     * connection. Framelink fetches are never faked.
     */
    private const val DESIGN_FLOW_ROUTING =
        "## Design flow (all default skills, ON)\\n" +
        "After the Huashu 6→3 step, for every design output: (1) PALETTES & FONTS — offer choices from the bundled " +
        "ui-ux-pro-max skill (load it with skill_view; its data lives in the installed skill dir under data/colors.csv " +
        "with 192 palettes and data/google-fonts.csv with font pairs): present 3 palette options and 3 font-pair options " +
        "and let the user pick, or pick and state the choice. (2) CRITIQUE — before final delivery, run the bundled " +
        "impeccable skill's critique/audit pass (load it with skill_view) to catch AI-design clichés (purple gradients, " +
        "generic hero layouts, lorem ipsum, mismatched spacing) and fix what it finds; this is an instruction-level pass " +
        "inside this run, not a separate validator. (3) ANIMATION — generated HTML animations must use GSAP (free, " +
        "including plugins) via CDN in the HTML output, following the bundled gsap-* skills. (4) REACT BITS — for " +
        "generated WEB prototypes only, React Bits components may be used via CDN/import in the HTML/React prototype " +
        "with attribution; native Android UI stays Hermes' own Compose components, never React Bits. (5) VIDEO — " +
        "HyperFrames workflow docs/templates (bundled hyperframes skills) describe HTML scene → video; HTML preview in " +
        "the Browser screen works now, but final video rendering runs ONLY after verifying the sandbox toolchain " +
        "(run `command -v chromium chromium-browser google-chrome ffmpeg` first) — if Chromium+ffmpeg are not present, " +
        "list video as not available on-phone and deliver the HTML scene + export package instead. (6) BLENDER — " +
        "no live Blender control on the phone; write Blender Python scripts (bundled blender-scripts skill) into " +
        "chat/project files for the user to run in desktop Blender. (7) FIGMA — when the user pastes a Figma link, " +
        "fetch its layout/style JSON with the framelink-figma MCP server's get_figma_data tool (needs the user's own " +
        "Figma API key in Settings → Environment), or via direct Figma REST API calls with the user's key if that " +
        "server is unavailable; generate code in Hermes' own style from the fetched data. Never fake a Figma fetch."

    /**
     * Team mode (Sno two-agent team + ECC engineering team, ON by
     * default, ACTIVE in the agent — not an opt-in). Uses Hermes'
     * existing delegate_task sub-agent system; no new runtime.
     * Honest provider note: delegate_task offers model_choice
     * (same_as_me / default_model / sub_model), NOT an arbitrary
     * provider picker — so the reviewer runs on a DIFFERENT provider
     * only when the user's configured default/sub models live on
     * another provider than the builder's; otherwise it runs
     * fresh-session on a different model of the same provider, and the
     * agent says so. Fresh session means: the reviewer brief contains
     * ONLY the deliverable, the original request, and the checklist —
     * never the builder's chat history.
     */
    private const val TEAM_MODE_ROUTING =
        "## Team mode (ON by default — builder + reviewer + specialists)\\n" +
        "For every build/deliverable task, work as a team via delegate_task (wait=true at each stage) and show which " +
        "specialist is working: (1) PLANNER — break the task into steps and acceptance criteria. (2) RESEARCHER — " +
        "research-first: check docs and existing code BEFORE writing anything. (3) IMPLEMENTER — build it. " +
        "(4) TESTER — run the real tests/checks (a testing skill, when loaded, triggers real test runs, not a claim). " +
        "(5) REVIEWER — a FRESH sub-agent session whose brief contains ONLY the deliverable, the original request, and " +
        "the checklist (never builder history); prefer a model_choice whose provider differs from the builder's when the " +
        "user has one configured, otherwise a different model of the same provider — and say which it is. The reviewer " +
        "either approves or lists concrete errors. (6) SECURITY AUDITOR — a security pass (a security skill, when loaded, " +
        "triggers this pass): secrets, injection, unsafe commands, data leaks. On errors, the implementer fixes them and " +
        "the reviewer re-checks ONCE. Only after reviewer + security approval is the deliverable presented as final. " +
        "Non-build chat (questions, explanations) does not need the team. The user can turn Team mode off in chat; when " +
        "off, work single-agent as before. Quota handoff is automatic (see below) and independent of Team mode.\\n" +
        "## Quota handoff (ON by default)\\n" +
        "If a provider call fails with a quota/rate-limit error, the app switches to the next configured provider and " +
        "resumes from the last checkpoint instead of restarting; the user is told which provider took over. If no " +
        "fallback provider is configured, the run stops honestly — never fake progress.\\n" +
        "## Nightly learning (approval-gated)\\n" +
        "A nightly job drafts proposed rules from the day's sessions into rules/proposed.json. NOTHING is adopted without " +
        "the user's approval in the morning brief (or /rules approve). Approved rules below are active; rejected rules " +
        "are never re-proposed."

    private const val ENVIRONMENT =
        "## Environment\nFor final deliverables, write files to /root/outputs and call send_file with each completed file. The app attaches changed files from that output folder automatically after tool execution. Use send_file for deliverables elsewhere. Use ordinary HTTPS or Markdown links for websites. Never claim a missing file is attached.\nYou run inside the Hermes Android app on the user's phone. Your terminal is a sandboxed Alpine Linux " +
        "(aarch64, PRoot — no root on the host, but you are root inside). Home is /root; HERMES_HOME is /root/.hermes. " +
        "Install software with `apk add <pkg>` (e.g. python3, py3-pip, git, nodejs, curl, ffmpeg). Shared phone storage is " +
        "at /sdcard only when the user enabled it in Settings. Long-running servers should use terminal(background=true) " +
        "and the process tool. The android_device tool reaches phone features (clipboard, notifications, open URLs, battery).\n\n" +
        "## Authority\nWork within the user's request, enabled tools and configured approval policy. Tool access and saved credentials do not grant blanket permission. " +
        "Do not bypass a denied approval or claim an unavailable integration works. Ask before destructive actions or external effects outside the authorized task. " +
        "Use /root/uploads for user attachments and /root/outputs for deliverables. Phone control requires the user's granted Android/Shizuku permissions. " +
        "Use connected GitHub credentials only for repository actions authorized by the user; never expose tokens in chat, logs or generated files."


    private val PLATFORM_HINTS = mapOf(
        "telegram" to "You are replying through Telegram. Keep messages readable on a phone; avoid wide tables; use short code blocks.",
        "discord" to "You are replying through Discord. Messages over 2000 chars are split; prefer concise answers and fenced code.",
        "slack" to "You are replying through Slack. Use Slack-friendly formatting (*bold*, `code`), avoid markdown tables.",
        "terminal" to "You are replying to the `hermes` CLI inside the sandbox terminal; plain text renders best.",
        "cron" to "You are running a scheduled task unattended. Do the work and produce the final report; nobody can answer questions.",
        "subagent" to "You are a sub-agent running unattended in the background. Work autonomously, don't ask questions, and finish with a concise report.",
        "bot" to "You are replying inside the Hermes app's Bot Mode. A message starting with \"Message from 🤖\" comes from another bot, not the user: answer it directly and concisely; your reply is returned to that bot automatically.",
    )

    fun build(runtime: HermesRuntime, agent: Agent): String {
        val s = runtime.settings
        val home = runtime.hermesHome
        val parts = mutableListOf<String>()
        val bot = agent.bot
        val soul = if (bot != null && !bot.isDefault) bot.soul.trim().ifBlank { null }
            else java.io.File(home, "SOUL.md").takeIf { it.exists() }?.readText()?.trim()
        parts += soul?.ifBlank { null } ?: DEFAULT_IDENTITY
        if (bot != null && !bot.isDefault) {
            parts += "## Your bot identity\nYou are ${bot.name} (@${bot.id})" +
                (if (bot.title.isNotBlank()) ", ${bot.title}" else "") + "." +
                (if (bot.description.isNotBlank()) " ${bot.description}" else "") +
                " You are one bot in a roster of Hermes agents, each with its own memory and chat."
        }
        parts += "For coding work in ${s.codingProject}, use the hermes-spec-workflow skill and coding_workflow tool: inspect instructions and Git state, record acceptance criteria, implement focused changes, review diffs, and verify with actual test evidence. Resume persisted tasks without replaying completed side effects. Ordinary conversation needs no specification workflow."
        if (s.specFirst) {
            // Spec-first is ON by default (settings.specFirst): build requests
            // first go through the bundled Spec Kit flow (constitution → spec
            // → plan → task checklist, shown in chat), then implementation
            // task by task. When off, this block is absent and chat is as before.
            parts += "Spec-first is ON for this chat: when the user asks to build something, first produce or update the constitution, spec, plan and a task checklist (using the bundled Spec Kit templates and the spec workflow), show that checklist in chat, then implement task by task, marking tasks done only with test evidence. Do not skip the spec/plan/checklist step for build requests. " +
                "LIVING SPEC.md (ThinkRail concept): at the start of every session in a project, read the project's SPEC.md first (it holds intent, decisions, and architecture notes); after each completed task, update SPEC.md with what changed and why, so intent survives across sessions. " +
                "COMPONENTS: when the user pastes a component prompt (from uberdesigner.ai or anywhere — their own input, never a copied library), treat it as the style instruction and implement it with the design pipeline below. " +
                "FILM: when the user says 'make a film' (or asks for a film), use the bundled lemo-film skill: pick one of its 66 styles or a saved MY-STYLES custom style, build scene-by-scene HTML/GSAP code, and render via the sandbox Chromium+ffmpeg attempt — or deliver the complete coded film package via ZIP export, naming exactly which render step needs desktop. " +
                "SEARCH: when the xerj MCP server is connected, prefer its search tools and use its exact file:line snippets instead of reading whole files; otherwise the BM25-ranked search_files tool is the live path. " +
                "TOKEN SAVER is ON: tool results reaching you were compressed on-device (JSON minify, comment stripping, repeated-line dedup, marked truncation); error lines and log tails are always preserved."
        }
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

        if (s.memoryEnabled) agent.memory.promptBlock("memory")?.let { parts += it }
        if (s.userProfileEnabled) agent.memory.promptBlock("user")?.let { parts += it }
        if ("message_bot" in toolNames) runtime.botHub.rosterPrompt(agent)?.let { parts += it }
        if ("delegate_task" in toolNames) runtime.agentJobs.roster()?.let { parts += it }
        if ("skill_view" in toolNames) {
            runtime.skills.indexBlock()?.let { parts += it }
            if (runtime.skills.find("huashu-design") != null) parts += DESIGN_SKILL_ROUTING
            if (runtime.skills.find("reverse-engineer-anything") != null) parts += REA_MODE_ROUTING
            if (runtime.skills.find("ui-ux-pro-max") != null || runtime.skills.find("impeccable") != null) parts += DESIGN_FLOW_ROUTING
            if (s.teamMode) parts += TEAM_MODE_ROUTING
            // User-approved learned rules (nightly learning): active only
            // after explicit approval; never auto-adopted (see RulesStore).
            runCatching {
                val approved = java.io.File(runtime.hermesHome, "rules/approved.md")
                if (approved.isFile) approved.readText().take(4000).ifBlank { null }?.let { parts += "## Learned rules (user-approved, apply to future runs)\\n$it" }
            }
        }

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
        val delivery = "\n\n## Deliverables\nWrite completed deliverables to /root/outputs and use send_file when available. Changed files there are attached automatically after tool execution; use send_file for other paths. Never claim an attachment exists without creating it. Use HTTP(S) links for websites."
        val coding = "\n\n## Coding workflows\nFor coding tasks, load the matching bundled skill with skill_view: explore-repository, implement-feature, review-code, verify-changes."
        val mcp = delivery + coding + runtime.mcp.promptBlock()?.let { "\n\n$it" }.orEmpty()
        val todos = agent.session.todos
        if (todos.isEmpty()) return mcp
        return mcp + "\n\n## Current task list\n" + todos.joinToString("\n") { t ->
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
