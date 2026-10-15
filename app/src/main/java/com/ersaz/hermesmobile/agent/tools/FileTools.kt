package com.ersaz.hermesmobile.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun ToolContext.resolve(path: String): File = runtime.sandbox.hostPath(path, agent.cwd)

class ReadFileTool : Tool(
    "read_file", "file",
    "Read a text file from the sandbox filesystem with line numbers. Paths are sandbox paths (/root/..., relative to cwd, ~). " +
        "Use offset/limit for large files.",
    "📖",
) {
    override val parameters = obj(
        "path" to str("File path"),
        "offset" to int("1-based line to start from (default 1)"),
        "limit" to int("Max lines to return (default 500)"),
        required = listOf("path"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val f = ctx.resolve(args.optString("path"))
        if (!f.exists()) return@withContext fail("File not found: ${args.optString("path")}")
        if (f.isDirectory) {
            val list = f.listFiles()?.sortedBy { it.name }?.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }
            return@withContext ok("is_directory" to true, "entries" to list)
        }
        if (f.length() > 20_000_000) return@withContext fail("File too large (${f.length()} bytes); use terminal tools like head/grep.")
        val bytes = f.readBytes()
        if (bytes.take(8000).any { it == 0.toByte() }) return@withContext fail("Binary file (${bytes.size} bytes) — not shown.")
        val lines = String(bytes).lines()
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)
        val slice = lines.drop(offset - 1).take(limit)
        val numbered = slice.mapIndexed { i, l -> "%6d|%s".format(offset + i, if (l.length > 2000) l.take(2000) + "…" else l) }
        JSONObject().put("content", numbered.joinToString("\n")).put("total_lines", lines.size)
            .put("showing", "${offset}-${offset + slice.size - 1}")
            .apply { if (offset + slice.size - 1 < lines.size) put("hint", "More lines remain; call again with offset=${offset + slice.size}.") }
            .toString()
    }
}

class WriteFileTool : Tool(
    "write_file", "file",
    "Write (create or overwrite) a file with the given content. Parent directories are created. Prefer patch for small edits.",
    "✍️",
) {
    override val parameters = obj(
        "path" to str("File path"),
        "content" to str("Full file content"),
        required = listOf("path", "content"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val f = ctx.resolve(args.optString("path"))
        f.parentFile?.mkdirs()
        val content = args.optString("content")
        com.ersaz.hermesmobile.coding.trackedProjectWrite(ctx.runtime, f) { f.writeText(content) }
        ok("path" to args.optString("path"), "bytes_written" to content.toByteArray().size, "lines" to content.lines().size)
    }
}

class PatchTool : Tool(
    "patch", "file",
    "Edit a file by exact string replacement. old_string must match exactly once unless replace_all=true. " +
        "Include enough surrounding context to make the match unique.",
    "🔧",
) {
    override val parameters = obj(
        "path" to str("File path"),
        "old_string" to str("Exact text to replace"),
        "new_string" to str("Replacement text"),
        "replace_all" to bool("Replace every occurrence"),
        required = listOf("path", "old_string", "new_string"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val f = ctx.resolve(args.optString("path"))
        if (!f.exists()) return@withContext fail("File not found: ${args.optString("path")}")
        val text = f.readText()
        val old = args.optString("old_string")
        val new = args.optString("new_string")
        if (old.isEmpty()) return@withContext fail("old_string is empty")
        var count = countOccurrences(text, old)
        var target = old
        if (count == 0) {
            // Fuzzy fallback: tolerate differing trailing whitespace / indentation (Hermes fuzzy_match, simplified).
            val norm = { s: String -> s.lines().joinToString("\n") { it.trimEnd() } }
            val nText = norm(text); val nOld = norm(old)
            if (nOld.isNotEmpty() && nText.contains(nOld) && nText == text) {
                target = nOld; count = 1
            } else return@withContext fail("old_string not found in file. Re-read the file and copy the exact text.")
        }
        if (count > 1 && !args.optBoolean("replace_all")) return@withContext fail("old_string matches $count times; add context or set replace_all=true.")
        val updated = if (args.optBoolean("replace_all")) text.replace(target, new) else text.replaceFirst(target, new)
        com.ersaz.hermesmobile.coding.trackedProjectWrite(ctx.runtime, f) { f.writeText(updated) }
        val line = text.substring(0, text.indexOf(target).coerceAtLeast(0)).count { it == '\n' } + 1
        ok("path" to args.optString("path"), "replacements" to if (args.optBoolean("replace_all")) count else 1, "first_line" to line)
    }
}

class SearchFilesTool : Tool(
    "search_files", "file",
    "Search the sandbox filesystem. target='content' greps file contents with a regex; target='files' finds files by glob name.",
    "🔎",
) {
    override val parameters = obj(
        "pattern" to str("Regex (content) or glob like *.py (files)"),
        "target" to str("What to search", listOf("content", "files")),
        "path" to str("Directory to search (default cwd)"),
        "file_glob" to str("Only search files matching this glob (content mode)"),
        "limit" to int("Max results (default 50)"),
        required = listOf("pattern"),
    )

    private fun globToRegex(glob: String) = Regex(
        "^" + glob.replace(".", "\\.").replace("**/", "(.*/)?").replace("*", "[^/]*").replace("?", ".") + "$",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val root = ctx.resolve(args.optString("path").ifBlank { ctx.agent.cwd })
        if (!root.exists()) return@withContext fail("Path not found")
        val limit = args.optInt("limit", 50).coerceIn(1, 500)
        val skipDirs = setOf(".git", "node_modules", "__pycache__", ".cache", "proc", "sys", "dev")
        val walk = root.walkTopDown().onEnter { it == root || it.name !in skipDirs }.filter { it.isFile }
        val results = JSONArray()
        if (args.optString("target", "content") == "files") {
            val rx = globToRegex(args.optString("pattern"))
            for (f in walk) {
                val rel = f.relativeTo(root).path
                if (rx.matches(f.name) || rx.matches(rel)) results.put(ctx.runtime.sandbox.guestPath(f))
                if (results.length() >= limit) break
            }
        } else {
            val rx = try { Regex(args.optString("pattern")) } catch (e: Exception) { return@withContext fail("Bad regex: ${e.message}") }
            val fileRx = args.optString("file_glob").ifBlank { null }?.let { globToRegex(it) }
            outer@ for (f in walk) {
                if (fileRx != null && !fileRx.matches(f.name)) continue
                if (f.length() > 5_000_000) continue
                val lines = try { f.readLines() } catch (_: Exception) { continue }
                if (lines.take(50).any { '\u0000' in it }) continue
                for ((i, l) in lines.withIndex()) {
                    if (rx.containsMatchIn(l)) {
                        results.put("${ctx.runtime.sandbox.guestPath(f)}:${i + 1}: ${l.trim().take(300)}")
                        if (results.length() >= limit) break@outer
                    }
                }
            }
        }
        JSONObject().put("matches", results).put("count", results.length())
            .apply { if (results.length() >= limit) put("truncated", true) }.toString()
    }
}

private fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var i = text.indexOf(needle)
    while (i >= 0) { count++; i = text.indexOf(needle, i + needle.length) }
    return count
}
