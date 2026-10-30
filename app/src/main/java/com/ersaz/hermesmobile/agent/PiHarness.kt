package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime

/**
 * Pi harness (concept only, implemented in Hermes' own code — no code, text
 * or assets copied from Pi or any other project): a second agent runtime
 * mode that runs the pi-coding-agent CLI (Earendil Works, MIT) inside the
 * existing Alpine sandbox via Node, in non-interactive print mode.
 *
 * Install: `npm install -g --ignore-scripts @earendil-works/pi-coding-agent`
 * (Node 22.19+ per Pi's docs; the sandbox Node version governs here).
 * Run: `pi --print --no-session --thinking <level> --model <model> "<prompt>"`
 * in the project directory.
 *
 * Honest limits, stated in the UI too:
 * - If Pi cannot be installed or run, status is "not installed" and the app
 *   falls back to the native Hermes engine — a Pi run is never faked.
 * - Pi uses Pi's own provider configuration inside the sandbox; a model
 *   selected in Hermes (e.g. a DeepSeek model) only works in Pi mode if Pi
 *   itself is configured for that provider. DeepSeek + Pi is therefore
 *   offered, not guaranteed.
 * - Hermes' per-tool approval gate does NOT intercept Pi-internal tool
 *   calls: a Pi run is started only by an explicit user button press, and
 *   that press is the approval for that run. Do not use Pi mode for
 *   unattended or approval-sensitive work.
 */
object PiHarness {
    const val PACKAGE = "@earendil-works/pi-coding-agent"

    /** Hermes reasoningEffort → Pi --thinking. Blank = Pi's own default (flag omitted). */
    fun mapThinking(effort: String): String? = when (effort.lowercase()) {
        "", "default" -> null
        "none", "off" -> "off"
        "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high" -> "high"
        "xhigh" -> "xhigh"
        "max" -> "max" // accepted by newer Pi builds; model support varies
        else -> "medium"
    }

    /** DeepSeek thinking models accept only low/high/max; map Hermes levels onto that set. */
    fun mapDeepSeekThinking(effort: String): String? = when (effort.lowercase()) {
        "", "default", "none", "off" -> null
        "low", "minimal" -> "low"
        "high", "medium" -> "high"
        "xhigh", "max" -> "max"
        else -> "high"
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun printCommand(model: String, thinking: String?, prompt: String): String {
        val parts = mutableListOf("pi", "--print", "--no-session")
        if (model.isNotBlank()) { parts += "--model"; parts += shellQuote(model) }
        if (thinking != null) { parts += "--thinking"; parts += thinking }
        parts += shellQuote(prompt)
        return parts.joinToString(" ")
    }
}

class PiRunner(private val rt: HermesRuntime) {
    /** "installed" (with version), "not installed", or "error: …". */
    suspend fun status(): String {
        val r = rt.sandbox.exec("command -v pi >/dev/null 2>&1 && pi --version", timeoutSec = 60)
        return if (r.exitCode == 0) "installed (${r.output.trim().take(40)})" else "not installed"
    }

    /** Install via npm inside the sandbox only if missing. Returns true only if `pi` then runs. */
    suspend fun ensureInstalled(): Boolean {
        if (status().startsWith("installed")) return true
        val install = rt.sandbox.exec(
            "npm install -g --ignore-scripts ${PiHarness.PACKAGE}",
            timeoutSec = 900,
        )
        if (install.exitCode != 0) return false
        return status().startsWith("installed")
    }

    /** One non-interactive Pi run in [workspaceGuestPath]. Null output = not installed/failed (caller falls back). */
    suspend fun runPrint(prompt: String, workspaceGuestPath: String, model: String, thinking: String?): String? {
        if (!ensureInstalled()) return null
        val cmd = PiHarness.printCommand(model, thinking, prompt)
        val r = rt.sandbox.exec(cmd, cwd = workspaceGuestPath, timeoutSec = 900)
        return if (r.exitCode == 0) r.output else null
    }
}
