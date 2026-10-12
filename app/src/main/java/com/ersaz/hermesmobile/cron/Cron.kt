package com.ersaz.hermesmobile.cron

import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Hermes' built-in scheduler, ported. Schedules accept the same forms as `hermes cron`:
 *   "30m" / "2h" / "1d"          one-shot after a delay
 *   "every 30m" / "every 2h"     recurring interval
 *   "0 9 * * *"                  5-field cron expression
 *   "2026-10-20T09:00"           one-shot at a timestamp
 */
data class CronJob(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String,
    var prompt: String,
    var schedule: String,
    var deliver: String = "origin", // origin | local | telegram | discord | slack | <platform>:<chatId>
    var origin: String? = null,     // platform:chatId that created it
    var enabled: Boolean = true,
    var nextRunAt: Long = 0,
    var lastRunAt: Long = 0,
    var lastStatus: String = "",
    var lastOutput: String = "",
    var runCount: Int = 0,
    var repeat: Int = 0, // 0 = unlimited for recurring
    var skills: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("prompt", prompt).put("schedule", schedule)
        .put("deliver", deliver).put("origin", origin).put("enabled", enabled).put("next_run_at", nextRunAt)
        .put("last_run_at", lastRunAt).put("last_status", lastStatus).put("last_output", lastOutput.take(4000))
        .put("run_count", runCount).put("repeat", repeat).put("skills", JSONArray(skills))

    fun describe(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val next = if (enabled && nextRunAt > 0) fmt.format(Date(nextRunAt)) else "—"
        return "[$id] ${name} — '${schedule}' → $deliver | next: $next | runs: $runCount${if (!enabled) " | paused" else ""}"
    }

    companion object {
        fun fromJson(o: JSONObject) = CronJob(
            id = o.optString("id"), name = o.optString("name"), prompt = o.optString("prompt"),
            schedule = o.optString("schedule"), deliver = o.optString("deliver", "origin"),
            origin = o.optStringOrNull("origin"), enabled = o.optBoolean("enabled", true),
            nextRunAt = o.optLong("next_run_at"), lastRunAt = o.optLong("last_run_at"),
            lastStatus = o.optString("last_status"), lastOutput = o.optString("last_output"),
            runCount = o.optInt("run_count"), repeat = o.optInt("repeat"),
            skills = o.optJSONArray("skills")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
    }
}

object Schedule {
    private val dur = Regex("^(\\d+)\\s*(s|sec|m|min|h|hr|d|day|w)s?$", RegexOption.IGNORE_CASE)

    fun parseDuration(s: String): Long? {
        val m = dur.matchEntire(s.trim()) ?: return null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2].lowercase().first()) {
            's' -> 1000L; 'm' -> 60_000L; 'h' -> 3_600_000L; 'd' -> 86_400_000L; 'w' -> 604_800_000L; else -> 60_000L
        }
    }

    fun isRecurring(schedule: String): Boolean {
        val s = schedule.trim().lowercase()
        return s.startsWith("every ") || s.split(Regex("\\s+")).size == 5
    }

    /** Validate and return a human error, or null when OK. */
    fun validate(schedule: String): String? =
        if (next(schedule, System.currentTimeMillis(), firstRun = true) == null)
            "Unrecognised schedule '$schedule'. Use '30m', 'every 2h', '0 9 * * *', or '2026-10-20T09:00'."
        else null

    fun next(schedule: String, from: Long, firstRun: Boolean): Long? {
        val s = schedule.trim()
        val lower = s.lowercase()
        if (lower.startsWith("every ")) {
            val d = parseDuration(lower.removePrefix("every ").trim()) ?: return null
            return from + d
        }
        parseDuration(s)?.let { return if (firstRun) from + it else null }
        if (s.split(Regex("\\s+")).size == 5) return nextCron(s, from)
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            try {
                val t = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(s)?.time ?: continue
                return if (firstRun && t > from) t else null
            } catch (_: Exception) {}
        }
        return null
    }

    private fun field(spec: String, min: Int, max: Int): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (part in spec.split(',')) {
            val (range, stepStr) = part.split('/').let { it[0] to it.getOrNull(1) }
            val step = stepStr?.toIntOrNull() ?: 1
            val (lo, hi) = when {
                range == "*" -> min to max
                '-' in range -> range.split('-').let { (it[0].toIntOrNull() ?: return null) to (it[1].toIntOrNull() ?: return null) }
                else -> (range.toIntOrNull() ?: return null).let { if (stepStr != null) it to max else it to it }
            }
            if (lo < min || hi > max || step <= 0) return null
            var v = lo
            while (v <= hi) { out += v; v += step }
        }
        return out
    }

    private fun nextCron(expr: String, from: Long): Long? {
        val p = expr.trim().split(Regex("\\s+"))
        val minutes = field(p[0], 0, 59) ?: return null
        val hours = field(p[1], 0, 23) ?: return null
        val doms = field(p[2], 1, 31) ?: return null
        val months = field(p[3], 1, 12) ?: return null
        val dows = field(p[4].replace("7", "0"), 0, 6) ?: return null
        val domStar = p[2] == "*"
        val dowStar = p[4] == "*"
        val c = Calendar.getInstance().apply {
            timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 1)
        }
        repeat(366 * 24 * 60) {
            val month = c.get(Calendar.MONTH) + 1
            if (month !in months) { c.add(Calendar.MONTH, 1); c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); return@repeat }
            val domOk = c.get(Calendar.DAY_OF_MONTH) in doms
            val dowOk = (c.get(Calendar.DAY_OF_WEEK) - 1) in dows
            val dayOk = when { domStar && dowStar -> true; domStar -> dowOk; dowStar -> domOk; else -> domOk || dowOk }
            if (!dayOk) { c.add(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); return@repeat }
            if (c.get(Calendar.HOUR_OF_DAY) !in hours) { c.add(Calendar.HOUR_OF_DAY, 1); c.set(Calendar.MINUTE, 0); return@repeat }
            if (c.get(Calendar.MINUTE) in minutes) return c.timeInMillis
            c.add(Calendar.MINUTE, 1)
        }
        return null
    }
}

class CronStore(private val file: File) {
    init { file.parentFile?.mkdirs() }

    @Synchronized
    fun all(): MutableList<CronJob> = try {
        if (!file.exists()) mutableListOf()
        else JSONObject(file.readText()).optJSONArray("jobs")?.objects()?.map { CronJob.fromJson(it) }?.toMutableList() ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    @Synchronized
    fun saveAll(jobs: List<CronJob>) {
        file.writeText(JSONObject().put("jobs", JSONArray().also { a -> jobs.forEach { a.put(it.toJson()) } }).toString(2))
    }

    @Synchronized
    fun add(job: CronJob): CronJob {
        job.nextRunAt = Schedule.next(job.schedule, System.currentTimeMillis(), firstRun = true) ?: 0
        val jobs = all(); jobs += job; saveAll(jobs)
        return job
    }

    @Synchronized
    fun update(id: String, block: (CronJob) -> Unit): CronJob? {
        val jobs = all()
        val job = jobs.firstOrNull { it.id == id || it.name.equals(id, true) } ?: return null
        block(job)
        saveAll(jobs)
        return job
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val jobs = all()
        val removed = jobs.removeAll { it.id == id || it.name.equals(id, true) }
        if (removed) saveAll(jobs)
        return removed
    }

    fun get(id: String) = all().firstOrNull { it.id == id || it.name.equals(id, true) }

    @Synchronized
    fun due(now: Long): List<CronJob> = all().filter { it.enabled && it.nextRunAt in 1..now }
}
