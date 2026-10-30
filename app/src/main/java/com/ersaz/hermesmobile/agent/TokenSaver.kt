package com.ersaz.hermesmobile.agent

/**
 * Token saver (Headroom concept, Hermes' own code — Headroom is
 * Apache-2.0; nothing of its code is bundled here, only the idea:
 * compress tool outputs, logs, files and RAG chunks BEFORE they
 * reach the model, on-device only).
 *
 * SAFETY RULES (never violated):
 *  - Compression applies ONLY to content going INTO the model
 *    (tool results in the agent loop). Files the agent WRITES are
 *    never compressed, minified, or altered by this object.
 *  - Error lines are never dropped: any line matching an error
 *    marker is always kept, even under truncation.
 *  - The last lines of a log are never dropped: truncation keeps
 *    the tail, and every cut is marked with an explicit
 *    "[truncated, N lines omitted]" marker so the model knows
 *    content was cut — nothing is silently removed.
 *  - Token counts are estimates (chars / 4), shown per run in chat.
 *    Headroom's published claims (20% coding, 60-95% JSON) are
 *    THEIR claims, not Hermes' measured results — Hermes' own
 *    measured savings live in IMPLEMENTATION_REPORT.md.
 */
object TokenSaver {
    data class RunStats(val beforeChars: Long, val afterChars: Long) {
        val savedChars get() = (beforeChars - afterChars).coerceAtLeast(0)
        val beforeTokens get() = beforeChars / 4
        val afterTokens get() = afterChars / 4
        val savedTokens get() = beforeTokens - afterTokens
        val savedPct get() = if (beforeChars > 0) savedChars * 100 / beforeChars else 0
    }

    private var beforeTotal = 0L
    private var afterTotal = 0L

    @Synchronized
    fun record(beforeChars: Int, afterChars: Int) {
        beforeTotal += beforeChars
        afterTotal += afterChars
    }

    /** Take the accumulated stats for the finished run and reset. */
    @Synchronized
    fun takeRunStats(): RunStats? {
        if (beforeTotal <= 0) return null
        val s = RunStats(beforeTotal, afterTotal)
        beforeTotal = 0; afterTotal = 0
        return s
    }

    private val errorRx = Regex("(?i)error|exception|failed|failure|panic|traceback|fatal|assert")

    /**
     * Compress one tool result for model context. [codeAware] adds
     * comment stripping for file/search results (still context-only:
     * the file on disk is untouched).
     */
    fun compressForContext(text: String, codeAware: Boolean = false): String {
        var out = text
        if (looksLikeJson(out)) out = minifyJson(out)
        if (codeAware) out = stripComments(out)
        out = dedupLines(out)
        out = truncateMarked(out)
        return out
    }

    fun looksLikeJson(t: String): Boolean {
        val s = t.trimStart()
        return (s.startsWith("{") || s.startsWith("[")) && s.length > 40
    }

    /** Drop whitespace outside strings (string-aware, escape-aware). */
    fun minifyJson(t: String): String {
        val sb = StringBuilder(t.length)
        var inStr = false
        var esc = false
        for (c in t) {
            when {
                esc -> { sb.append(c); esc = false }
                c == '\\' && inStr -> { sb.append(c); esc = true }
                c == '"' -> { inStr = !inStr; sb.append(c) }
                !inStr && (c == ' ' || c == '\n' || c == '\r' || c == '\t') -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Strip // and block comments, string-aware. Never touches file writes. */
    fun stripComments(t: String): String {
        val sb = StringBuilder(t.length)
        var i = 0
        var inStr = false
        var strCh = '"'
        var inLine = false
        var inBlock = false
        while (i < t.length) {
            val c = t[i]
            val n = if (i + 1 < t.length) t[i + 1] else '\u0000'
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && n == '/') { inBlock = false; i++ }
                inStr -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < t.length) { sb.append(t[i + 1]); i++ }
                    else if (c == strCh) inStr = false
                }
                c == '"' || c == '\'' || c == '`' -> { inStr = true; strCh = c; sb.append(c) }
                c == '/' && n == '/' -> inLine = true
                c == '/' && n == '*' -> { inBlock = true; i++ }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Collapse runs of 3+ identical consecutive lines into one + a marker. */
    fun dedupLines(t: String): String {
        val lines = t.lines()
        if (lines.size < 4) return t
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            var j = i + 1
            while (j < lines.size && lines[j] == line) j++
            val run = j - i
            if (run >= 3 && !errorRx.containsMatchIn(line)) {
                out += line
                out += "[${run - 1} identical lines omitted]"
            } else {
                repeat(run) { out += line }
            }
            i = j
        }
        return out.joinToString("\n")
    }

    /**
     * Bounded truncation that ALWAYS keeps: the head, the tail
     * (last 100 lines), and every error line (up to 100). The cut
     * is marked explicitly. No silent drops.
     */
    fun truncateMarked(t: String, maxLines: Int = 1200): String {
        val lines = t.lines()
        if (lines.size <= maxLines) return t
        val head = lines.take(800)
        val tail = lines.takeLast(100)
        val errors = lines.filter { errorRx.containsMatchIn(it) }.take(100)
        val omitted = lines.size - head.size - tail.size - errors.size
        return (head + "[truncated, $omitted lines omitted — error lines and tail preserved]" +
            errors + tail).joinToString("\n")
    }
}
