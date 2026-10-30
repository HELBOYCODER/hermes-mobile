package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import java.io.File
import org.json.JSONObject

/**
 * Long-running resilience (Copilot-harness concept, adopted into
 * Hermes' own native engine): checkpoint/resume of an agent run.
 *
 * Honest scope: the checkpoint persists PROGRESS — session title,
 * turn count, last user request, last assistant text, timestamp — to
 * $HERMES_HOME/runs/checkpoint.json after each completed assistant
 * turn (saved from ChatScreen, which owns the visible turn list).
 * "Resume" does NOT replay tool calls or restore hidden agent state:
 * it fills the composer with a resume prompt carrying the checkpoint
 * summary, and the user presses send. An interrupted run therefore
 * continues from its summary instead of restarting from scratch,
 * but it is a summary-based resume, not a state restore — and the UI
 * says exactly that.
 */
object RunCheckpoint {
    data class Checkpoint(
        val session: String, val turns: Int,
        val lastUser: String, val lastAssistant: String, val at: Long,
    )

    private fun file(rt: HermesRuntime) = File(rt.hermesHome, "runs/checkpoint.json")

    fun save(rt: HermesRuntime, session: String, turns: Int, lastUser: String, lastAssistant: String) {
        runCatching {
            val f = file(rt); f.parentFile?.mkdirs()
            f.writeText(JSONObject()
                .put("session", session).put("turns", turns)
                .put("lastUser", lastUser.take(2000)).put("lastAssistant", lastAssistant.take(4000))
                .put("at", System.currentTimeMillis()).toString())
        }
    }

    fun load(rt: HermesRuntime): Checkpoint? = runCatching {
        val f = file(rt); if (!f.isFile) return null
        val o = JSONObject(f.readText())
        Checkpoint(o.optString("session"), o.optInt("turns"),
            o.optString("lastUser"), o.optString("lastAssistant"), o.optLong("at"))
    }.getOrNull()

    fun resumePrompt(c: Checkpoint): String =
        "Resuming the interrupted run (checkpoint: session \"${c.session}\", ${c.turns} turns). " +
        "Last request was: ${c.lastUser.take(500)}. " +
        "Last assistant progress was: ${c.lastAssistant.take(1000)}. " +
        "Continue from that progress instead of restarting; do not redo completed steps."
}
