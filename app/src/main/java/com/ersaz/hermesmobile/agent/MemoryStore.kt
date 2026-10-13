package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.core.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Port of Hermes' memory tool store: two bounded files of §-delimited entries,
 * $HERMES_HOME/memories/MEMORY.md (agent notes) and USER.md (user profile).
 */
class MemoryStore(private val dir: File, private val memoryLimit: Int = 2200, private val userLimit: Int = 1375) {

    init { dir.mkdirs() }

    fun file(target: String) = File(dir, if (target == "user") "USER.md" else "MEMORY.md")
    fun limit(target: String) = if (target == "user") userLimit else memoryLimit

    @Synchronized
    fun entries(target: String): MutableList<String> {
        val f = file(target)
        if (!f.exists()) return mutableListOf()
        return f.readText().split(DELIM).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun charCount(list: List<String>) = list.joinToString(DELIM).length

    @Synchronized
    private fun write(target: String, list: List<String>) {
        file(target).writeText(list.joinToString(DELIM))
    }

    fun usage(target: String): String = "${charCount(entries(target))}/${limit(target)}"

    /** Apply one or many ops atomically; budget is checked on the final result only. */
    @Synchronized
    fun apply(target: String, ops: List<JSONObject>): JSONObject {
        val list = entries(target)
        for (op in ops) {
            val action = op.optString("action")
            val content = op.optString("content").ifEmpty { op.optString("new_text") }.trim()
            val old = op.optString("old_text").trim()
            when (action) {
                "add" -> {
                    if (content.isEmpty()) return err("add requires content", target, list)
                    if (list.any { it == content }) continue
                    list += content
                }
                "replace", "remove" -> {
                    if (old.isEmpty()) return err("$action requires old_text", target, list)
                    val matches = list.indices.filter { old in list[it] }
                    if (matches.isEmpty()) return err("No entry contains old_text: '$old'", target, list)
                    if (matches.size > 1) return err("old_text matches ${matches.size} entries — be more specific", target, list)
                    if (action == "remove") list.removeAt(matches[0])
                    else {
                        if (content.isEmpty()) return err("replace requires content", target, list)
                        list[matches[0]] = content
                    }
                }
                else -> return err("Unknown action '$action' (use add, replace, remove)", target, list)
            }
        }
        val count = charCount(list)
        if (count > limit(target)) {
            return err("Memory full: result would be $count/${limit(target)} chars. Remove or consolidate entries in the same call.", target, entries(target))
        }
        write(target, list)
        return JSONObject().put("success", true).put("target", target)
            .put("usage", "$count/${limit(target)}").put("entries", list.size)
    }

    private fun err(msg: String, target: String, list: List<String>) = JSONObject()
        .put("success", false).put("error", msg).put("target", target)
        .put("current_entries", JSONArray(list)).put("usage", "${charCount(list)}/${limit(target)}")

    /** Frozen snapshot injected into the system prompt at session start. */
    fun promptBlock(target: String): String? {
        val list = entries(target)
        if (list.isEmpty()) return null
        val count = charCount(list)
        val pct = count * 100 / limit(target)
        val title = if (target == "user") "USER PROFILE (who the user is)" else "MEMORY (your personal notes)"
        val bar = "═".repeat(46)
        return "$bar\n$title [$pct% — $count/${limit(target)} chars]\n$bar\n" + list.joinToString(DELIM)
    }

    companion object {
        const val DELIM = "\n§\n"

        val SCHEMA: JSONObject by lazy {
            JSONObject("""
            {"name":"memory","description":"Save durable facts to persistent memory that survive across sessions. Memory is injected into every future turn, so keep entries compact and high-signal.\n\nHOW: make ALL your changes in ONE call via an 'operations' array (each item: {action, content?, old_text?}). The batch applies atomically and the char limit is checked only on the FINAL result.\n\nWHEN: only for facts that apply to EVERY session regardless of task: who the user is, stable environment facts, standing conventions with no task home. Procedures belong in skills (skill_manage).\n\nTARGETS: 'user' = who the user is (name, role, preferences, style). 'memory' = your notes (environment, conventions, tool quirks, lessons).\n\nSKIP: trivial info, task progress, temporary TODO state.",
             "parameters":{"type":"object","properties":{
               "action":{"type":"string","enum":["add","replace","remove"],"description":"Single-op shape. Omit when using 'operations'."},
               "target":{"type":"string","enum":["memory","user"],"description":"'memory' for personal notes, 'user' for user profile."},
               "content":{"type":"string","description":"Entry content (add/replace). For replace it is the COMPLETE new entry."},
               "old_text":{"type":"string","description":"Short unique substring identifying the entry to replace/remove."},
               "operations":{"type":"array","description":"Batch of {action, content?, old_text?} applied atomically.","items":{"type":"object","properties":{"action":{"type":"string","enum":["add","replace","remove"]},"content":{"type":"string"},"old_text":{"type":"string"}},"required":["action"]}}
             },"required":["target"]}}
            """.trimIndent())
        }

        fun opsFrom(args: JSONObject): List<JSONObject> =
            args.optJSONArray("operations")?.objects() ?: listOf(args)
    }
}
