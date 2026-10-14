package com.ersaz.hermesmobile.agent

/**
 * Spots a model stuck calling the same tool with the same arguments and getting the same answer.
 * First it nudges (a note appended to the tool result); if it keeps going, the turn is stopped.
 */
class ToolLoopDetector(private val warnAt: Int = 3, private val stopAt: Int = 6) {
    enum class Verdict { OK, WARN, STOP }

    private var lastKey: String? = null
    private var repeats = 0

    fun record(tool: String, args: String, result: String): Verdict {
        val key = tool + "\u0000" + args.trim() + "\u0000" + result.hashCode()
        repeats = if (key == lastKey) repeats + 1 else 1
        lastKey = key
        return when {
            repeats >= stopAt -> Verdict.STOP
            repeats >= warnAt -> Verdict.WARN
            else -> Verdict.OK
        }
    }

    fun count() = repeats

    fun nudge(tool: String) = "\n\n[Loop detector: you've now called `$tool` $repeats times in a row with identical arguments and received the same result. " +
        "Repeating it won't help — change your approach, use different arguments or another tool, or tell the user what's blocking you.]"
}
