package com.ersaz.hermesmobile.agent.jobs

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.ToolCall
import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SubAgentLimits {
    const val MAX_COUNT = 10
    const val NAME_MAX = 40
    /** The only free text that reaches the main conversation's system prompt on every turn. */
    const val DESCRIPTION_MAX = 200
    const val INSTRUCTIONS_MAX = 4000
    const val MAX_CONCURRENT = 3
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 60
}

/**
 * A named sub-agent the main model can delegate to. [model] pins a model id; blank = Auto, where the delegating
 * model picks with `model_choice` (default: the same model as the conversation).
 */
data class SubAgentDef(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String,
    var description: String,
    var instructions: String = "",
    var model: String = "",
    val builtIn: Boolean = false,
) {
    fun clamped() = copy(name = name.take(SubAgentLimits.NAME_MAX), description = description.take(SubAgentLimits.DESCRIPTION_MAX),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX))

    fun toJson() = JSONObject().put("id", id).put("name", name).put("description", description)
        .put("instructions", instructions).put("model", model).put("built_in", builtIn)

    companion object {
        const val GENERAL = "general"
        fun fromJson(o: JSONObject) = SubAgentDef(o.optString("id"), o.optString("name"), o.optString("description"),
            o.optString("instructions"), o.optString("model"), o.optBoolean("built_in"))
        fun builtIn() = SubAgentDef("builtin", GENERAL, "General-purpose worker for any self-contained task: research, code changes, file work, data gathering.", builtIn = true)
    }
}

/** Roster persisted at `$HERMES_HOME/subagents.json`; the built-in "general" agent is always first. */
class SubAgentStore(private val file: File) {
    @Synchronized
    fun all(): List<SubAgentDef> {
        val stored = try {
            if (file.exists()) JSONObject(file.readText()).optJSONArray("agents")?.objects()?.map { SubAgentDef.fromJson(it).clamped() } ?: emptyList() else emptyList()
        } catch (_: Exception) { emptyList() }
        val builtin = stored.firstOrNull { it.builtIn } ?: SubAgentDef.builtIn()
        return (listOf(builtin) + stored.filter { !it.builtIn }).take(SubAgentLimits.MAX_COUNT)
    }

    @Synchronized
    fun save(list: List<SubAgentDef>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("agents", JSONArray().also { a -> list.forEach { a.put(it.clamped().toJson()) } }).toString(2))
    }

    fun upsert(d: SubAgentDef) { val l = all().toMutableList(); val i = l.indexOfFirst { it.id == d.id }; if (i >= 0) l[i] = d else if (l.size < SubAgentLimits.MAX_COUNT) l += d; save(l) }
    fun remove(id: String) { save(all().filter { it.id != id || it.builtIn }) }

    /** Match by name, case-insensitively; unknown/blank names fall back to the general agent. */
    fun resolve(name: String?): SubAgentDef {
        val all = all()
        return all.firstOrNull { it.name.equals(name?.trim(), true) } ?: all.first()
    }
}

enum class JobState { QUEUED, RUNNING, DONE, CANCELLED, FAILED, TIMEOUT, INTERRUPTED;
    val finished get() = this == DONE || this == CANCELLED || this == FAILED || this == TIMEOUT
}

class AgentJob(
    val id: String,
    val parentSessionId: String,
    val agentName: String,
    val task: String,
    val maxMinutes: Int,
    val model: String,
    val childSessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    @Volatile var state: JobState = JobState.QUEUED
    @Volatile var startedAt: Long = 0
    @Volatile var finishedAt: Long = 0
    @Volatile var currentTool: String = ""
    @Volatile var result: String = ""
    @Volatile var steerPending: String = ""
    @Volatile var steerDelivered: Boolean = false
    internal var runner: Job? = null
    internal var agent: Agent? = null
    internal val done = CompletableDeferred<Unit>()

    val elapsedSec: Long get() = ((if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - (if (startedAt > 0) startedAt else createdAt)) / 1000

    fun toJson(full: Boolean = true): JSONObject = JSONObject().put("job_id", id).put("agent", agentName).put("status", state.name.lowercase())
        .put("task", task.take(200)).put("elapsed_s", elapsedSec).put("model", model).put("child_session_id", childSessionId)
        .also { o ->
            if (currentTool.isNotBlank() && !state.finished) o.put("current_tool", currentTool)
            if (full && state.finished && result.isNotBlank()) o.put("result", result)
        }
}

/**
 * Runs sub-agents in hidden child sessions, at most [SubAgentLimits.MAX_CONCURRENT] at once (the rest queue),
 * and posts each result back to the parent conversation as a new message — which wakes the main agent, so
 * delegation needs no polling. Interrupted runs (app killed) can be resumed.
 */
class AgentJobs(private val rt: HermesRuntime) {
    val store = SubAgentStore(File(rt.hermesHome, "subagents.json"))
    private val jobs = ConcurrentHashMap<String, AgentJob>()
    private val slots = Semaphore(SubAgentLimits.MAX_CONCURRENT)
    private val _tick = MutableStateFlow(0)
    /** Bumps on every job state change (UI). */
    val tick: StateFlow<Int> = _tick
    private val jobsFile = File(rt.hermesHome, "agent-jobs.json")

    /** A parent surface (chat screen, bot runner) that wants background results delivered as messages. */
    fun interface Sink { suspend fun deliver(text: String) }
    private val sinks = ConcurrentHashMap<String, Sink>()
    fun registerSink(sessionId: String, sink: Sink) { sinks[sessionId] = sink }
    fun unregisterSink(sessionId: String, sink: Sink) { sinks.remove(sessionId, sink) }

    init { loadInterrupted() }

    fun all(): List<AgentJob> = jobs.values.sortedByDescending { it.createdAt }
    fun forSession(sessionId: String) = all().filter { it.parentSessionId == sessionId }.sortedBy { it.createdAt }
    fun active() = all().filter { !it.state.finished && it.state != JobState.INTERRUPTED }
    fun find(sessionId: String, prefix: String?) = forSession(sessionId).let { l -> if (prefix.isNullOrBlank()) null else l.firstOrNull { it.id.startsWith(prefix) } }

    fun enabled() = rt.settings.subAgentsEnabled

    // ---------------------------------------------------------------- start

    class Resolved(val def: SubAgentDef, val model: String)

    /** Which model a sub-agent runs on: the pinned one, else the model's `model_choice`. */
    fun resolveModel(def: SubAgentDef, parent: Agent, choice: String?): String = when {
        def.model.isNotBlank() -> def.model
        choice == "default_model" -> rt.settings.model
        choice == "sub_model" -> rt.settings.subModel.ifBlank { parent.model() }
        else -> parent.model()
    }

    fun start(parent: Agent, task: String, agentName: String?, choice: String?, context: String?, maxMinutes: Int, toolsets: Set<String>? = null, wait: Boolean): AgentJob {
        val def = store.resolve(agentName)
        val model = resolveModel(def, parent, choice)
        val child = Session(source = "subagent", title = "↳ ${def.name}: ${task.take(48)}", botId = parent.session.botId, modelOverride = model)
        rt.sessions.save(child)
        val job = AgentJob(UUID.randomUUID().toString().take(8), parent.session.id, def.name, task, maxMinutes.coerceIn(1, SubAgentLimits.MAX_MINUTES), model, child.id)
        jobs[job.id] = job
        launch(job, def, parent, brief(def, task, context), toolsets ?: DEFAULT_TOOLSETS, child, wait)
        persist(); _tick.value++
        return job
    }

    private fun brief(def: SubAgentDef, task: String, context: String?) = buildString {
        append("You are a sub-agent (\"${def.name}\") working for another agent. You cannot ask the user questions and you do not see their conversation. ")
        append("Complete the task autonomously, then end with a concise report: what you did, what you found, and any files you changed.\n\n")
        if (def.instructions.isNotBlank()) append("## Your role\n${def.instructions}\n\n")
        append("## Task\n$task")
        if (!context.isNullOrBlank()) append("\n\n## Context\n$context")
    }

    private fun launch(job: AgentJob, def: SubAgentDef, parent: Agent, prompt: String, toolsets: Set<String>, childSession: Session, wait: Boolean, resumeText: String? = null) {
        job.runner = rt.scope.launch(Dispatchers.IO) {
            try {
                slots.acquire()
            } catch (e: CancellationException) { finish(job, JobState.CANCELLED, "Cancelled before it started.", wait, parent); return@launch }
            try {
                job.state = JobState.RUNNING; job.startedAt = System.currentTimeMillis(); _tick.value++
                val agent = Agent(rt, childSession, "subagent", toolsets, parent.depth + 1, parent.bot).also { job.agent = it; it.cwd = parent.cwd }
                if (job.steerPending.isNotBlank()) agent.steer(job.steerPending)
                val listener = object : AgentListener {
                    override fun onToolStart(call: ToolCall) { job.currentTool = call.name; _tick.value++ }
                }
                val out = withTimeoutOrNull(job.maxMinutes * 60_000L) { agent.runTurn(resumeText ?: prompt, emptyList(), listener) }
                when {
                    out == null -> { agent.interrupt(); finish(job, JobState.TIMEOUT, partial(childSession, "Timed out after ${job.maxMinutes} min."), wait, parent) }
                    agent.interrupted -> finish(job, JobState.CANCELLED, partial(childSession, "Cancelled."), wait, parent)
                    else -> finish(job, JobState.DONE, out.ifBlank { "(no output)" }, wait, parent)
                }
            } catch (e: CancellationException) {
                finish(job, JobState.CANCELLED, partial(childSession, "Cancelled."), wait, parent)
            } catch (e: Exception) {
                finish(job, JobState.FAILED, "Failed: ${e.message}", wait, parent)
            } finally { slots.release() }
        }
    }

    private fun partial(child: Session, note: String): String {
        val last = child.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
        return if (last.isNullOrBlank()) note else "$note Partial result:\n$last"
    }

    private fun finish(job: AgentJob, state: JobState, result: String, wait: Boolean, parent: Agent) {
        if (job.state.finished) return
        job.state = state; job.result = result; job.finishedAt = System.currentTimeMillis(); job.currentTool = ""
        rt.log.add(if (state == JobState.FAILED) 'E' else 'I', "agent", "${job.agentName} ${job.id} ${state.name.lowercase()} after ${job.elapsedSec}s")
        persist(); _tick.value++
        job.done.complete(Unit)
        if (!wait) rt.scope.launch(Dispatchers.IO) { postBack(job) }
    }

    /** Waits for a job started with wait=true. A user message arriving meanwhile is the caller's concern (it just stops waiting). */
    suspend fun await(job: AgentJob) { job.done.await() }

    private suspend fun postBack(job: AgentJob) {
        val missed = if (job.steerPending.isNotBlank() && !job.steerDelivered) "\n(Your steer \"${job.steerPending.take(80)}\" arrived too late to apply.)" else ""
        val text = "[Background task ${job.state.name.lowercase()}: ${job.agentName} · job ${job.id} · ${job.elapsedSec}s]\n${job.result}$missed"
        val sink = sinks[job.parentSessionId]
        if (sink != null) { sink.deliver(text); return }
        // Parent conversation isn't on screen: run the follow-up headlessly and notify.
        val s = rt.sessions.load(job.parentSessionId) ?: return
        val bot = s.botId?.let { rt.bots.get(it) }?.takeUnless { it.isDefault }
        val a = Agent(rt, s, if (s.source in setOf("telegram", "discord", "slack")) s.source else "app", bot = bot)
        val reply = try { a.runTurn(text, emptyList(), object : AgentListener {}) } catch (_: Exception) { job.result }
        rt.notify("Sub-agent finished: ${job.agentName}", reply)
    }

    // ---------------------------------------------------------------- control

    fun cancel(job: AgentJob): Boolean {
        if (job.state.finished) return false
        job.agent?.interrupt()
        job.runner?.cancel()
        if (job.state == JobState.QUEUED || job.state == JobState.INTERRUPTED) {
            // Never started, so there is no loop to report back: close it out here.
            job.state = JobState.CANCELLED; job.finishedAt = System.currentTimeMillis(); job.result = "Cancelled before it started."
            job.done.complete(Unit); persist(); _tick.value++
        }
        return true
    }

    fun steer(job: AgentJob, message: String): Boolean {
        if (job.state != JobState.RUNNING && job.state != JobState.QUEUED) return false
        job.steerPending = message
        val a = job.agent
        if (a != null) { a.steer(message); job.steerDelivered = true }
        return true
    }

    /** Restart runs the app lost when it was killed, continuing their saved child session. */
    fun resume(parent: Agent, only: String?): List<AgentJob> {
        val targets = forSession(parent.session.id).filter { it.state == JobState.INTERRUPTED && (only.isNullOrBlank() || it.id.startsWith(only) || it.childSessionId.startsWith(only)) }
        targets.forEach { j ->
            val child = rt.sessions.load(j.childSessionId) ?: return@forEach
            j.state = JobState.QUEUED; j.finishedAt = 0
            launch(j, store.resolve(j.agentName), parent, j.task, DEFAULT_TOOLSETS, child, wait = false, resumeText = "The app was restarted while you were working. Continue the task from where you left off and finish with your report.")
        }
        persist(); _tick.value++
        return targets
    }

    fun roster(): String? {
        if (!enabled()) return null
        val defs = store.all()
        return buildString {
            append("## Sub-agents\nYou can delegate self-contained work with delegate_task — it runs in the background in its own context while you continue, and its result arrives as a new message ")
            append("(never poll, never promise to report back). Delegate work needing many rounds of exploration, bulky output you only need a conclusion from, or independent sub-problems to run in parallel; ")
            append("don't delegate what you can finish in one or two tool calls. Write the task as a complete brief — the sub-agent sees nothing else. Available:\n")
            defs.forEach { append("- ${it.name}: ${it.description}\n") }
        }.trimEnd()
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        try {
            val arr = JSONArray()
            all().take(60).forEach { j ->
                arr.put(JSONObject().put("id", j.id).put("parent", j.parentSessionId).put("agent", j.agentName).put("task", j.task).put("max", j.maxMinutes)
                    .put("model", j.model).put("child", j.childSessionId).put("created", j.createdAt).put("state", j.state.name).put("result", j.result.take(8000))
                    .put("finished", j.finishedAt))
            }
            jobsFile.writeText(JSONObject().put("jobs", arr).toString())
        } catch (_: Exception) {}
    }

    private fun loadInterrupted() {
        try {
            if (!jobsFile.exists()) return
            JSONObject(jobsFile.readText()).optJSONArray("jobs")?.objects()?.forEach { o ->
                val j = AgentJob(o.optString("id"), o.optString("parent"), o.optString("agent"), o.optString("task"), o.optInt("max", 10), o.optString("model"), o.optString("child"), o.optLong("created"))
                val st = runCatching { JobState.valueOf(o.optString("state")) }.getOrDefault(JobState.DONE)
                // Anything that was in flight when the process died can be resumed.
                j.state = if (st.finished) st else JobState.INTERRUPTED
                j.result = o.optString("result"); j.finishedAt = o.optLong("finished")
                if (j.state == JobState.INTERRUPTED) j.done.complete(Unit)
                jobs[j.id] = j
            }
        } catch (_: Exception) {}
    }

    companion object {
        val DEFAULT_TOOLSETS = setOf("terminal", "file", "web", "skills", "todo", "mcp", "session_search", "vision", "browser", "device")
    }
}
