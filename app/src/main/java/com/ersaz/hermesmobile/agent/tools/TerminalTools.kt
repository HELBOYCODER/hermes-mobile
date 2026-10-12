package com.ersaz.hermesmobile.agent.tools

import org.json.JSONArray
import org.json.JSONObject

/** Patterns Hermes asks approval for before running (approval.py, condensed). */
object DangerousCommands {
    private val patterns = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|${"$"}HOME|/\*|\*)(\s|$)""") to "recursive delete of a root/home path",
        Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r""") to "forced recursive delete",
        Regex("""\bmkfs(\.\w+)?\b""") to "filesystem format",
        Regex("""\bdd\s+.*\bof=/dev/""") to "raw write to a device",
        Regex(""":\(\)\s*\{\s*:\|:&\s*\};:""") to "fork bomb",
        Regex("""\bchmod\s+(-R\s+)?[0-7]*777\s+/""") to "world-writable permissions on system paths",
        Regex("""\b(curl|wget)\b[^|]*\|\s*(ba|z|da)?sh\b""") to "piping a download straight into a shell",
        Regex("""\bgit\s+push\s+.*--force""") to "force push",
        Regex("""\bgit\s+reset\s+--hard""") to "hard reset discards changes",
        Regex(""">\s*/dev/sd[a-z]""") to "overwriting a block device",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "power control",
        Regex("""\bkill\s+-9\s+-1\b""") to "kill all processes",
        Regex("""\bDROP\s+(TABLE|DATABASE)\b""", RegexOption.IGNORE_CASE) to "destructive SQL",
    )

    fun check(command: String): String? = patterns.firstOrNull { it.first.containsMatchIn(command) }?.second
}

class TerminalTool : Tool(
    "terminal", "terminal",
    "Execute a shell command in the sandboxed Alpine Linux environment (busybox sh; install tools with `apk add`). " +
        "The working directory persists between calls. Use background=true for servers/long jobs and manage them with the " +
        "process tool. Output is capped; redirect huge output to a file and read it with read_file.",
    "💻",
) {
    override val parameters = obj(
        "command" to str("The shell command to run"),
        "background" to bool("Run in the background and return a process id immediately"),
        "timeout" to int("Timeout in seconds (default from settings, max 3600)"),
        "workdir" to str("Working directory for this command (defaults to the persistent cwd)"),
        required = listOf("command"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return fail("command is required")
        val agent = ctx.agent
        val rt = ctx.runtime

        DangerousCommands.check(command)?.let { reason ->
            val approvalOff = rt.settings.approvalMode == "off" || agent.yolo
            if (!approvalOff && command !in agent.sessionApprovals) {
                val approved = ctx.listener.requestApproval(command, reason)
                if (!approved) return fail("Command denied by user approval gate ($reason). Do not retry it; ask the user or choose a safer approach.")
            }
        }

        val cwd = args.optString("workdir").ifBlank { agent.cwd }
        if (args.optBoolean("background")) {
            val e = rt.processes.start(command, cwd)
            return ok("process_id" to e.id, "status" to "running",
                "hint" to "Use process(action='poll', session_id='${e.id}') to read output, 'kill' to stop.")
        }
        val timeout = args.optInt("timeout", rt.settings.commandTimeout).coerceIn(1, 3600)
        val r = rt.sandbox.exec(command, cwd, timeout)
        if (args.optString("workdir").isBlank()) agent.cwd = r.cwd
        return JSONObject()
            .put("output", r.output.ifEmpty { "(no output)" })
            .put("exit_code", r.exitCode)
            .put("cwd", r.cwd)
            .apply { if (r.timedOut) put("error", "Command timed out after ${timeout}s and was killed. Use background=true for long tasks.") }
            .toString()
    }
}

class ProcessTool : Tool(
    "process", "terminal",
    "Manage background processes started with terminal(background=true): list, poll (new output), log (tail), wait, write (stdin), kill.",
    "⚙️",
) {
    override val parameters = obj(
        "action" to str("Action", listOf("list", "poll", "log", "wait", "write", "kill")),
        "session_id" to str("Process id (e.g. proc_1)"),
        "data" to str("Text to write to stdin (for write)"),
        "timeout" to int("Seconds to wait (for wait, default 30)"),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val reg = ctx.runtime.processes
        val id = args.optString("session_id")
        return when (args.optString("action")) {
            "list" -> JSONObject().put("processes", JSONArray().also { a ->
                reg.list().forEach { e ->
                    a.put(JSONObject().put("id", e.id).put("command", e.command.take(200))
                        .put("running", e.isRunning).put("exit_code", e.exitCode ?: JSONObject.NULL))
                }
            }).toString()
            "poll" -> reg.get(id)?.let { e ->
                JSONObject().put("id", id).put("running", e.isRunning).put("exit_code", e.exitCode ?: JSONObject.NULL)
                    .put("new_output", com.ersaz.hermesmobile.core.truncateMiddle(reg.poll(id).orEmpty(), 30_000)).toString()
            } ?: fail("No process $id")
            "log" -> reg.log(id)?.let { ok("log" to it) } ?: fail("No process $id")
            "wait" -> {
                val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reg.waitFor(id, args.optInt("timeout", 30).coerceIn(1, 600)) }
                reg.get(id)?.let { e ->
                    JSONObject().put("finished", done).put("exit_code", e.exitCode ?: JSONObject.NULL)
                        .put("new_output", com.ersaz.hermesmobile.core.truncateMiddle(reg.poll(id).orEmpty(), 30_000)).toString()
                } ?: fail("No process $id")
            }
            "write" -> if (reg.write(id, args.optString("data"))) ok() else fail("Could not write to $id")
            "kill" -> if (reg.kill(id)) ok("killed" to id) else fail("No process $id")
            else -> fail("Unknown action")
        }
    }
}
