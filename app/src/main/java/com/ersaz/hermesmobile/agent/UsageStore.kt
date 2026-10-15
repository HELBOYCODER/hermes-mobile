package com.ersaz.hermesmobile.agent

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Token usage per day and model, kept in `$HERMES_HOME/usage.json` (the "Usage stats" screen reads it). */
class UsageStore(private val file: File) {
    data class Row(val day: String, val model: String, val calls: Int, val prompt: Long, val completion: Long)

    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Synchronized
    fun record(model: String, prompt: Long, completion: Long, now: Long = System.currentTimeMillis()) {
        val root = read()
        val day = dayFmt.format(Date(now))
        val d = root.optJSONObject(day) ?: JSONObject().also { root.put(day, it) }
        val m = d.optJSONObject(model) ?: JSONObject().also { d.put(model, it) }
        m.put("calls", m.optInt("calls") + 1).put("prompt", m.optLong("prompt") + prompt).put("completion", m.optLong("completion") + completion)
        // Keep ~13 months.
        val keys = root.keys().asSequence().toList().sorted()
        keys.dropLast(400).forEach { root.remove(it) }
        file.parentFile?.mkdirs(); file.writeText(root.toString())
    }

    @Synchronized
    fun rows(days: Int, now: Long = System.currentTimeMillis()): List<Row> {
        val root = read()
        val cutoff = dayFmt.format(Date(now - days * 86_400_000L))
        return root.keys().asSequence().filter { it >= cutoff }.sorted().flatMap { day ->
            val d = root.optJSONObject(day) ?: return@flatMap emptySequence<Row>()
            d.keys().asSequence().mapNotNull { model -> val m = d.optJSONObject(model) ?: return@mapNotNull null; Row(day, model, m.optInt("calls").coerceAtLeast(0), m.optLong("prompt").coerceAtLeast(0), m.optLong("completion").coerceAtLeast(0)) }
        }.toList()
    }

    private fun read(): JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
}
