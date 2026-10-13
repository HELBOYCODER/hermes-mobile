package com.ersaz.hermesmobile.commands

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.Personalities
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.cron.CronJob
import com.ersaz.hermesmobile.cron.Schedule
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.Skins
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Surface that slash commands act on: app chat, CLI bridge, or a messaging chat. */
interface CommandHost {
    val runtime: HermesRuntime
    val agent: Agent
    val platform: String
    val chatKey: String?
    fun isBusy(): Boolean
    fun newSession(): Agent
    fun resumeSession(session: Session): Agent
    fun stop()
    fun approve(always: Boolean): Boolean = false
    fun deny(): Boolean = false
    fun setHome(): String? = null
    /** Run [prompt] in a separate background session and report when done. */
    fun background(prompt: String) {}
}

sealed class CommandResult {
    data class Reply(val text: String) : CommandResult()
    /** Send [text] to the agent as the user's prompt (e.g. /retry, /plan, /<skill>). */
    data class Prompt(val text: String, val echo: String? = null, val ephemeral: Boolean = false) : CommandResult()
    data object Silent : CommandResult()
}

data class CommandDef(val name: String, val description: String, val category: String, val args: String = "",
                      val aliases: List<String> = emptyList(), val surfaces: Set<String>? = null)

object Commands {
    val defs = listOf(
        CommandDef("mcp", "Manage MCP servers: list, add <url|command|json>, login/reconnect/enable/disable/remove <id>", "Tools", "[subcommand]"),
        CommandDef("new", "Start a new session (fresh history)", "Session", aliases = listOf("reset", "clear")),
        CommandDef("retry", "Retry the last message", "Session"),
        CommandDef("undo", "Back up N user turns (default 1)", "Session", "[N]"),
        CommandDef("title", "Set a title for the current session", "Session", "[name]"),
        CommandDef("compress", "Compress conversation context", "Session", "[focus]"),
        CommandDef("stop", "Interrupt the running agent and kill background processes", "Session"),
        CommandDef("steer", "Inject guidance after the next tool call without interrupting", "Session", "<message>"),
        CommandDef("btw", "Ask a side question without adding it to the conversation", "Session", "<question>"),
        CommandDef("bg", "Run a prompt in a separate background session", "Session", "<prompt>"),
        CommandDef("status", "Show session, model, token and context info", "Session"),
        CommandDef("history", "Show conversation history", "Session"),
        CommandDef("save", "Export the conversation to ~/.hermes/exports", "Session"),
        CommandDef("sessions", "List recent sessions", "Session"),
        CommandDef("resume", "Resume a previous session by id or title", "Session", "<id|title>"),
        CommandDef("sethome", "Set this chat as the platform's home channel", "Session"),
        CommandDef("approve", "Approve a pending dangerous command", "Session", "[always]"),
        CommandDef("deny", "Deny a pending dangerous command", "Session"),
        CommandDef("plan", "Write a markdown implementation plan without executing", "Session", "<task>"),
        CommandDef("refine", "Review this conversation and save lessons to memory/skills", "Session"),
        CommandDef("review", "Spawn a subagent to review the work just discussed", "Session"),
        CommandDef("init", "Generate or update AGENTS.md project instructions", "Session"),
        CommandDef("learn", "Learn a reusable skill from what you describe", "Tools & Skills", "<description>"),
        CommandDef("model", "Show or switch model (provider:model switches provider too)", "Configuration", "[model]"),
        CommandDef("provider", "Show or switch provider", "Configuration", "[name]"),
        CommandDef("personality", "Set a predefined personality", "Configuration", "[name|none]"),
        CommandDef("skin", "Show or change the display skin/theme", "Configuration", "[name]"),
        CommandDef("reasoning", "Reasoning effort / display", "Configuration", "[none|low|medium|high|show|hide]"),
        CommandDef("verbose", "Cycle tool progress display: off → new → all → verbose", "Configuration"),
        CommandDef("yolo", "Toggle YOLO mode (skip dangerous-command approvals)", "Configuration"),
        CommandDef("config", "Show current configuration", "Configuration"),
        CommandDef("tools", "Manage tools: list | enable | disable <name>", "Tools & Skills", "[list|enable|disable] [name]"),
        CommandDef("toolsets", "List toolsets", "Tools & Skills", "[enable|disable] [name]"),
        CommandDef("skills", "List, view, search or delete skills", "Tools & Skills", "[list|view|search|delete] [name]"),
        CommandDef("memory", "Show persistent memory", "Tools & Skills"),
        CommandDef("cron", "Manage scheduled tasks", "Tools & Skills", "[list|add|pause|resume|remove|run]"),
        CommandDef("platforms", "Show gateway/messaging platform status", "Info"),
        CommandDef("usage", "Show token usage for this session", "Info"),
        CommandDef("insights", "Usage insights across sessions", "Info", "[days]"),
        CommandDef("version", "Show Hermes version", "Info", aliases = listOf("v")),
        CommandDef("whoami", "Show this chat's identity and platform", "Info"),
        CommandDef("help", "Show available commands", "Info", aliases = listOf("commands")),
    )

    private fun find(name: String) = defs.firstOrNull { it.name == name || name in it.aliases }

    /** Autocomplete candidates for the app/terminal input bar. */
    fun suggestions(runtime: HermesRuntime, prefix: String): List<Pair<String, String>> {
        val p = prefix.removePrefix("/").lowercase()
        val builtins = defs.filter { it.name.startsWith(p) }.map { "/${it.name}" + (if (it.args.isNotEmpty()) " " else "") to it.description }
        val skills = runtime.skills.list().filter { it.name.startsWith(p) }.map { "/${it.name} " to "⚡ skill: ${it.description.take(60)}" }
        return (builtins + skills).take(30)
    }

    suspend fun handle(input: String, host: CommandHost): CommandResult? {
        val text = input.trim()
        if (!text.startsWith("/") || text.length < 2) return null
        val name = text.substring(1).substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        val rt = host.runtime
        val s = rt.settings
        val agent = host.agent
        val session = agent.session

        val def = find(name)
        if (def == null) {
            // /<skill-name> invokes a skill, like Hermes' skill slash commands.
            val skill = rt.skills.find(name) ?: return CommandResult.Reply("Unknown command /$name — try /help")
            val body = skill.file.readText()
            return CommandResult.Prompt(
                "[SYSTEM: The user invoked the \"${skill.name}\" skill. Its instructions follow; apply them.]\n\n$body\n\n" +
                    "User instruction: ${arg.ifBlank { "(none — run the skill's default workflow)" }}",
                echo = text,
            )
        }

        return when (def.name) {
            "help" -> CommandResult.Reply(helpText(rt, arg))
            "new" -> { host.newSession(); CommandResult.Reply("✨ New session started.") }
            "retry" -> {
                if (host.isBusy()) return CommandResult.Reply("Agent is busy — /stop first.")
                val last = agent.undo(1).firstOrNull() ?: return CommandResult.Reply("Nothing to retry.")
                CommandResult.Prompt(last, echo = "↻ $last")
            }
            "undo" -> {
                if (host.isBusy()) return CommandResult.Reply("Agent is busy — /stop first.")
                val n = arg.toIntOrNull()?.coerceIn(1, 50) ?: 1
                val removed = agent.undo(n)
                CommandResult.Reply(if (removed.isEmpty()) "Nothing to undo." else "↶ Removed ${removed.size} turn(s). Last: \"${removed.last().take(80)}\"")
            }
            "title" -> {
                if (arg.isBlank()) CommandResult.Reply("Title: ${session.displayTitle()}")
                else { session.title = arg.take(80); rt.sessions.save(session); CommandResult.Reply("Title set: ${session.title}") }
            }
            "compress" -> {
                if (host.isBusy()) return CommandResult.Reply("Agent is busy — /stop first.")
                CommandResult.Reply(agent.compress(focus = arg.ifBlank { null }))
            }
            "stop" -> {
                host.stop()
                val killed = rt.processes.killAll()
                CommandResult.Reply("⏹ Stopped." + if (killed > 0) " Killed $killed background process(es)." else "")
            }
            "steer" -> {
                if (arg.isBlank()) return CommandResult.Reply("Usage: /steer <message>")
                if (!host.isBusy()) return CommandResult.Prompt(arg)
                agent.steer(arg); CommandResult.Reply("🧭 Steering note queued for the next step.")
            }
            "btw" -> {
                if (arg.isBlank()) return CommandResult.Reply("Usage: /btw <question>")
                val context = session.messages.takeLast(12).joinToString("\n") { "${it.role}: ${it.content.take(600)}" }
                val answer = rt.auxComplete("Conversation so far:\n$context\n\nSide question (answer briefly): $arg")
                CommandResult.Reply("💭 " + (answer ?: "(no answer — model call failed)"))
            }
            "bg" -> {
                if (arg.isBlank()) return CommandResult.Reply("Usage: /bg <prompt>")
                host.background(arg); CommandResult.Reply("🚀 Running in a background session. I'll report back when it's done.")
            }
            "status" -> CommandResult.Reply(statusText(host))
            "usage" -> CommandResult.Reply(
                "📊 Session usage\n• API calls: ${session.apiCalls}\n• Prompt tokens: ${"%,d".format(session.promptTokens)}\n" +
                    "• Completion tokens: ${"%,d".format(session.completionTokens)}\n" +
                    "• Context: ~${"%,d".format(maxOf(session.lastPromptTokens, agent.estimateTokens()))} / ${"%,d".format(s.contextLength)}\n" +
                    "• Compressions: ${session.compressions}"
            )
            "insights" -> CommandResult.Reply(insights(rt, arg.toIntOrNull() ?: 30))
            "history" -> CommandResult.Reply(
                session.messages.filter { it.role == "user" || (it.role == "assistant" && it.content.isNotBlank()) }
                    .takeLast(30).joinToString("\n\n") { "${if (it.role == "user") "● You" else "☤ Hermes"}: ${it.content.take(400)}" }
                    .ifBlank { "(empty)" }
            )
            "save" -> {
                val dir = java.io.File(rt.hermesHome, "exports").apply { mkdirs() }
                val f = java.io.File(dir, "${session.id}.md")
                f.writeText("# ${session.displayTitle()}\n\n" + session.messages.filter { it.role != "tool" && it.content.isNotBlank() }
                    .joinToString("\n\n") { "**${it.role}**:\n\n${it.content}" })
                CommandResult.Reply("💾 Saved to ${rt.sandbox.guestPath(f)}")
            }
            "sessions" -> CommandResult.Reply(
                rt.sessions.list().take(15).joinToString("\n") { "• ${it.id.takeLast(13)}  [${it.source}] ${it.title}" }
                    .ifBlank { "No sessions yet." } + "\n\nResume with /resume <id or title>"
            )
            "resume" -> {
                if (arg.isBlank()) return CommandResult.Reply("Usage: /resume <id|title>")
                val found = rt.sessions.list().firstOrNull { it.id.endsWith(arg) || it.id == arg }?.let { rt.sessions.load(it.id) }
                    ?: rt.sessions.findByTitle(arg) ?: return CommandResult.Reply("No session matching '$arg'.")
                host.resumeSession(found)
                CommandResult.Reply("↺ Resumed: ${found.displayTitle()} (${found.messages.size} messages)")
            }
            "sethome" -> CommandResult.Reply(host.setHome()?.let { "🏠 Home channel set: $it" } ?: "Home channels apply to messaging platforms only.")
            "approve" -> CommandResult.Reply(if (host.approve(arg == "always" || arg == "session")) "✅ Approved." else "No pending approval.")
            "deny" -> CommandResult.Reply(if (host.deny()) "🚫 Denied." else "No pending approval.")
            "plan" -> if (arg.isBlank()) CommandResult.Reply("Usage: /plan <task>") else CommandResult.Prompt(
                "Write a markdown implementation plan for the task below. Investigate as needed (read files, search), but do NOT " +
                    "modify anything except the plan file. Save it to /root/.hermes/plans/<short-slug>.md and summarize it.\n\nTask: $arg",
                echo = text,
            )
            "refine" -> CommandResult.Prompt(
                "Review this conversation now. Save durable facts about me to memory (target=user), environment facts to memory, " +
                    "and any reusable procedure, pitfall or correction to the relevant skill via skill_manage (create one if needed). " +
                    "Report briefly what you saved.", echo = text,
            )
            "review" -> CommandResult.Prompt(
                "Use delegate_task to spawn an independent subagent that critically reviews the work we just discussed " +
                    "(correctness, edge cases, security, simplicity). Give it full context. Then summarize its findings.", echo = text,
            )
            "init" -> CommandResult.Prompt(
                "Scan the current working directory (${agent.cwd}) and write or update AGENTS.md there with concise project " +
                    "instructions: purpose, layout, build/test commands, conventions. Keep it under 150 lines.", echo = text,
            )
            "learn" -> if (arg.isBlank()) CommandResult.Reply("Usage: /learn <what to learn: a URL, a folder, or a description>") else CommandResult.Prompt(
                "Learn a reusable skill from the following. Research it as needed, then create a well-structured skill with " +
                    "skill_manage(action='create') including frontmatter (name, description), when-to-use, steps and pitfalls.\n\nSource: $arg",
                echo = text,
            )
            "model" -> {
                if (arg.isBlank()) return CommandResult.Reply("🤖 Model: ${agent.model()}\nProvider: ${s.provider} (${s.baseUrl})\n\nSwitch: /model <name> or /model <provider>:<name>  (add --global to persist)")
                val global = arg.contains("--global") || host.platform == "app"
                val spec = arg.replace("--global", "").trim()
                val providerId = spec.substringBefore(':', "").takeIf { p -> Providers.all.any { it.id == p } }
                val model = if (providerId != null) spec.substringAfter(':') else spec
                if (providerId != null) {
                    val p = Providers.byId(providerId)
                    s.provider = p.id; if (p.baseUrl.isNotBlank()) s.baseUrl = p.baseUrl
                }
                if (global) { s.model = model; session.modelOverride = null } else session.modelOverride = model
                rt.sessions.save(session)
                CommandResult.Reply("🤖 Model → $model" + (if (providerId != null) " via $providerId" else "") + if (global) " (saved)" else " (this session)")
            }
            "provider" -> {
                if (arg.isBlank()) return CommandResult.Reply("Provider: ${s.provider}\nAvailable: " + Providers.all.joinToString(", ") { it.id })
                val p = Providers.all.firstOrNull { it.id == arg.lowercase() } ?: return CommandResult.Reply("Unknown provider. Options: " + Providers.all.joinToString(", ") { it.id })
                s.provider = p.id; if (p.baseUrl.isNotBlank()) s.baseUrl = p.baseUrl; if (p.defaultModel.isNotBlank()) s.model = p.defaultModel
                CommandResult.Reply("Provider → ${p.label} (${s.baseUrl}), model ${s.model}. Set the API key in Settings if needed.")
            }
            "personality" -> {
                if (arg.isBlank() || arg == "list") return CommandResult.Reply(
                    "🎭 Personality: ${s.personality.ifBlank { "none" }}\n\n" + Personalities.BUILTIN.entries.joinToString("\n") { "• ${it.key} — ${it.value.take(60)}…" } +
                        "\n\nUse /personality <name> or /personality none"
                )
                val n = arg.lowercase()
                if (n != "none" && Personalities.resolve(n) == null) return CommandResult.Reply("Unknown personality '$arg'.")
                s.personality = if (n == "none") "" else n
                session.systemPrompt = null // rebuild on next turn
                rt.sessions.save(session)
                CommandResult.Reply("🎭 Personality → ${if (n == "none") "none" else n}")
            }
            "skin" -> {
                if (arg.isBlank()) return CommandResult.Reply("🎨 Skin: ${s.skin}\n" + Skins.all.joinToString("\n") { "• ${it.name} — ${it.description}" })
                val skin = Skins.all.firstOrNull { it.name == arg.lowercase() } ?: return CommandResult.Reply("Unknown skin '$arg'.")
                s.skin = skin.name
                CommandResult.Reply("🎨 Skin → ${skin.name}: ${skin.description}")
            }
            "reasoning" -> {
                when (arg.lowercase()) {
                    "" -> CommandResult.Reply("🧠 Reasoning effort: ${s.reasoningEffort.ifBlank { "default" }}, display: ${if (s.showReasoning) "show" else "hide"}")
                    "show", "on" -> { s.showReasoning = true; CommandResult.Reply("🧠 Reasoning display on") }
                    "hide", "off" -> { s.showReasoning = false; CommandResult.Reply("🧠 Reasoning display off") }
                    "none", "minimal", "low", "medium", "high", "xhigh" -> { s.reasoningEffort = arg.lowercase(); CommandResult.Reply("🧠 Reasoning effort → $arg") }
                    else -> CommandResult.Reply("Usage: /reasoning [none|low|medium|high|show|hide]")
                }
            }
            "verbose" -> {
                val order = listOf("off", "new", "all", "verbose")
                val next = order[(order.indexOf(s.toolProgress) + 1) % order.size]
                s.toolProgress = next; s.gatewayToolProgress = next
                CommandResult.Reply("🔈 Tool progress: $next")
            }
            "yolo" -> {
                agent.yolo = !agent.yolo
                CommandResult.Reply(if (agent.yolo) "⚡ YOLO on — dangerous commands run without approval (this session)." else "🛡️ YOLO off — approvals required.")
            }
            "config" -> CommandResult.Reply(
                "⚙️ Config\n• provider: ${s.provider}\n• base_url: ${s.baseUrl}\n• model: ${s.model}\n• api_key: ${if (rt.usesNousLogin()) "Nous Portal login (${s.nousAccount.ifBlank { "signed in" }})" else if (s.apiKey.isBlank()) "(none)" else "••••" + s.apiKey.takeLast(4)}\n" +
                    "• personality: ${s.personality.ifBlank { "none" }}\n• skin: ${s.skin}\n• max_iterations: ${s.maxIterations}\n" +
                    "• context_length: ${s.contextLength}\n• approvals: ${s.approvalMode}\n• disabled toolsets: ${s.disabledToolsets.ifBlank { "none" }}"
            )
            "tools" -> {
                val parts = arg.split(' ').filter { it.isNotBlank() }
                when (parts.firstOrNull()) {
                    null, "list" -> {
                        val active = agent.tools().map { it.name }.toSet()
                        CommandResult.Reply("🔧 Tools\n" + rt.tools.all.joinToString("\n") { t ->
                            "${if (t.name in active) "✓" else "✗"} ${t.emoji} ${t.name} [${t.toolset}]"
                        })
                    }
                    "enable", "disable" -> {
                        val en = parts[0] == "enable"
                        parts.drop(1).forEach { s.setToolEnabled(it, en) }
                        session.systemPrompt = null
                        CommandResult.Reply("${if (en) "Enabled" else "Disabled"}: ${parts.drop(1).joinToString()}")
                    }
                    else -> CommandResult.Reply("Usage: /tools [list|enable|disable] <name…>")
                }
            }
            "toolsets" -> {
                val parts = arg.split(' ').filter { it.isNotBlank() }
                if (parts.size >= 2 && parts[0] in setOf("enable", "disable")) {
                    parts.drop(1).forEach { s.setToolsetEnabled(it, parts[0] == "enable") }
                    session.systemPrompt = null
                    return CommandResult.Reply("Toolsets ${parts[0]}d: ${parts.drop(1).joinToString()}")
                }
                val disabled = s.disabledToolsetSet()
                CommandResult.Reply("🧰 Toolsets\n" + rt.tools.toolsets.joinToString("\n") { "${if (it.name in disabled) "✗" else "✓"} ${it.name} — ${it.description}" })
            }
            "skills" -> {
                val sub = arg.substringBefore(' ').lowercase()
                val rest = arg.substringAfter(' ', "").trim()
                when (sub) {
                    "", "list" -> CommandResult.Reply(rt.skills.list().groupBy { it.category }.entries.joinToString("\n\n") { (cat, list) ->
                        "📁 $cat\n" + list.joinToString("\n") { "  • /${it.name} — ${it.description.take(70)}" }
                    }.ifBlank { "No skills installed yet. Ask Hermes to create one, or /learn <topic>." })
                    "view" -> rt.skills.find(rest)?.let { CommandResult.Reply(it.file.readText().take(3500)) } ?: CommandResult.Reply("No skill '$rest'")
                    "search" -> CommandResult.Reply(rt.skills.list().filter { it.name.contains(rest, true) || it.description.contains(rest, true) }
                        .joinToString("\n") { "• ${it.name} — ${it.description}" }.ifBlank { "No matches." })
                    "delete", "remove" -> rt.skills.find(rest)?.let { rt.skills.delete(it); CommandResult.Reply("🗑️ Deleted skill ${it.name}") } ?: CommandResult.Reply("No skill '$rest'")
                    else -> CommandResult.Reply("Usage: /skills [list|view|search|delete] <name>")
                }
            }
            "memory" -> host.agent.memory.let { mem -> CommandResult.Reply(
                "🧠 MEMORY.md (${mem.usage("memory")})\n" + mem.entries("memory").joinToString("\n") { "• $it" }.ifBlank { "(empty)" } +
                    "\n\n👤 USER.md (${mem.usage("user")})\n" + mem.entries("user").joinToString("\n") { "• $it" }.ifBlank { "(empty)" }
            ) }
            "mcp" -> mcp(arg, rt)
            "cron" -> cron(arg, host)
            "platforms" -> CommandResult.Reply(rt.gateway.statusText())
            "version" -> CommandResult.Reply("☤ Hermes Agent for Android ${HermesRuntime.VERSION}\nSandbox: Alpine Linux on PRoot (${if (rt.sandbox.isInstalled()) "installed" else "not installed"})")
            "whoami" -> CommandResult.Reply("Platform: ${host.platform}\nChat: ${host.chatKey ?: "local"}\nSession: ${session.id}")
            else -> CommandResult.Reply("/${def.name} is not available here.")
        }
    }

    private fun mcp(arg: String, rt: HermesRuntime): CommandResult {
        val sub = arg.substringBefore(' ').lowercase()
        val rest = arg.substringAfter(' ', "").trim()
        val m = rt.mcp
        fun line(s: com.ersaz.hermesmobile.mcp.McpServer) = "• ${s.id} — " + when (val st = m.statusOf(s.id)) {
            is com.ersaz.hermesmobile.mcp.McpStatus.Connected -> "✅ ${st.tools} tools"
            is com.ersaz.hermesmobile.mcp.McpStatus.NeedsAuth -> "🔑 needs sign-in (/mcp login ${s.id})"
            is com.ersaz.hermesmobile.mcp.McpStatus.Failed -> "❌ ${st.message.take(100)}"
            is com.ersaz.hermesmobile.mcp.McpStatus.Connecting -> "⏳ ${st.step}"
            com.ersaz.hermesmobile.mcp.McpStatus.Disabled, null -> if (s.enabled) "…" else "⏸ disabled"
        } + "\n   ${s.summary.take(80)}"
        return when (sub) {
            "", "list" -> CommandResult.Reply(m.servers().joinToString("\n") { line(it) }.ifBlank { "No MCP servers. Add one: /mcp add https://… (or open MCP servers in the menu)." })
            "add" -> { val r = m.addFromInput(rest); CommandResult.Reply(r.error ?: "Added ${r.ids.joinToString()} — connecting (it will open the sign-in page if needed).") }
            "login", "signin" -> if (m.store.get(rest) == null) CommandResult.Reply("No server '$rest'.") else { m.signInAsync(rest); CommandResult.Reply("Opening sign-in for $rest…") }
            "reconnect", "retry" -> if (m.store.get(rest) == null) CommandResult.Reply("No server '$rest'.") else { m.reconnectAsync(rest); CommandResult.Reply("Reconnecting $rest…") }
            "enable", "disable" -> if (m.store.get(rest) == null) CommandResult.Reply("No server '$rest'.") else { m.setEnabled(rest, sub == "enable"); CommandResult.Reply("$rest ${sub}d.") }
            "remove", "delete" -> if (m.store.get(rest) == null) CommandResult.Reply("No server '$rest'.") else { m.remove(rest); CommandResult.Reply("Removed $rest.") }
            else -> CommandResult.Reply("Usage: /mcp [list | add <url|command|json> | login <id> | reconnect <id> | enable <id> | disable <id> | remove <id>]")
        }
    }

    private fun cron(arg: String, host: CommandHost): CommandResult {
        val rt = host.runtime
        val sub = arg.substringBefore(' ').lowercase()
        val rest = arg.substringAfter(' ', "").trim()
        return when (sub) {
            "", "list" -> CommandResult.Reply(rt.cron.all().joinToString("\n") { it.describe() }.ifBlank {
                "No scheduled tasks.\nAdd: /cron add \"every 1d\" Summarize the news\n  or: /cron add \"0 9 * * *\" Morning briefing"
            })
            "add", "create" -> {
                val m = Regex("^\"([^\"]+)\"\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).find(rest)
                    ?: Regex("^(\\S+)\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).find(rest)
                    ?: return CommandResult.Reply("Usage: /cron add \"<schedule>\" <prompt>")
                val schedule = m.groupValues[1]
                Schedule.validate(schedule)?.let { return CommandResult.Reply(it) }
                val job = rt.cron.add(CronJob(name = m.groupValues[2].take(40), prompt = m.groupValues[2], schedule = schedule,
                    deliver = "origin", origin = host.chatKey ?: "local"))
                rt.ensureServiceForCron()
                CommandResult.Reply("⏰ Scheduled: ${job.describe()}")
            }
            "pause" -> CommandResult.Reply(rt.cron.update(rest) { it.enabled = false }?.let { "⏸ Paused ${it.id}" } ?: "No job $rest")
            "resume" -> CommandResult.Reply(rt.cron.update(rest) { j ->
                j.enabled = true; j.nextRunAt = Schedule.next(j.schedule, System.currentTimeMillis(), true) ?: 0
            }?.let { "▶️ Resumed ${it.id}" } ?: "No job $rest")
            "remove", "rm", "delete" -> CommandResult.Reply(if (rt.cron.remove(rest)) "🗑️ Removed $rest" else "No job $rest")
            "run" -> CommandResult.Reply(rt.cron.get(rest)?.let { rt.cronRunner.runNowAsync(it); "▶️ Running ${it.id} now" } ?: "No job $rest")
            else -> CommandResult.Reply("Usage: /cron [list|add|pause|resume|remove|run]")
        }
    }

    private fun statusText(host: CommandHost): String {
        val rt = host.runtime
        val a = host.agent
        val s = a.session
        val ctx = maxOf(s.lastPromptTokens, a.estimateTokens())
        val pct = ctx * 100 / rt.settings.contextLength.coerceAtLeast(1)
        return "☤ Hermes status\n• Session: ${s.displayTitle()} (${s.id})\n• Platform: ${host.platform}\n• Model: ${a.model()}\n" +
            "• Provider: ${rt.settings.provider}\n• Messages: ${s.messages.size}\n• Context: ~${"%,d".format(ctx)} tokens ($pct%)\n" +
            "• Tokens used: ${"%,d".format(s.promptTokens + s.completionTokens)}\n• Busy: ${if (host.isBusy()) "yes" else "no"}\n" +
            "• Sandbox: ${rt.sandbox.state.value::class.simpleName}  cwd ${a.cwd}\n• Background processes: ${rt.processes.list().count { it.isRunning }}"
    }

    private fun insights(rt: HermesRuntime, days: Int): String {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val sessions = rt.sessions.list().filter { it.updatedAt >= since }.mapNotNull { rt.sessions.load(it.id) }
        val tokens = sessions.sumOf { it.promptTokens + it.completionTokens }
        val toolCounts = sessions.flatMap { s -> s.messages.flatMap { m -> m.toolCalls.map { it.name } } }.groupingBy { it }.eachCount()
        val bySource = sessions.groupingBy { it.source }.eachCount()
        return "📈 Insights (last $days days)\n• Sessions: ${sessions.size}\n• Tokens: ${"%,d".format(tokens)}\n" +
            "• By platform: ${bySource.entries.joinToString { "${it.key} ${it.value}" }.ifBlank { "—" }}\n" +
            "• Top tools: ${toolCounts.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" }.ifBlank { "—" }}"
    }

    private fun helpText(rt: HermesRuntime, filter: String): String {
        val sb = StringBuilder("(^_^)? Available Commands\n")
        defs.filter { filter.isBlank() || it.name.contains(filter) || it.description.contains(filter, true) }
            .groupBy { it.category }.forEach { (cat, list) ->
                sb.append("\n$cat\n")
                list.forEach { sb.append("  /${it.name}${if (it.args.isNotEmpty()) " ${it.args}" else ""} — ${it.description}\n") }
            }
        val skills = rt.skills.list()
        if (skills.isNotEmpty() && filter.isBlank()) sb.append("\nSkills\n  " + skills.joinToString("  ") { "/${it.name}" } + "\n")
        return sb.toString().trimEnd()
    }

    fun timestamp(ms: Long): String = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))
}
