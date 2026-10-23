package com.ersaz.hermesmobile.cron

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Executes due cron jobs in fresh sessions and delivers the result (Hermes cron/scheduler.py). */
class CronRunner(private val rt: HermesRuntime) {
    private var ticker: Job? = null
    private val running = ConcurrentHashMap.newKeySet<String>()

    // start()/stop() are called from the service and the UI on different threads; unsynchronized, two
    // tickers could be launched and every due job would be checked twice per tick.
    @Synchronized
    fun start() {
        if (ticker?.isActive == true) return
        ticker = rt.scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    rt.cron.due(System.currentTimeMillis()).forEach { runNowAsync(it) }
                } catch (_: Exception) {}
                delay(30_000)
            }
        }
    }

    @Synchronized
    fun stop() { ticker?.cancel(); ticker = null }

    fun runNowAsync(job: CronJob) {
        if (!running.add(job.id)) return
        // Advance the schedule first so a slow run doesn't fire twice.
        rt.cron.update(job.id) { j ->
            j.nextRunAt = if (Schedule.isRecurring(j.schedule)) Schedule.next(j.schedule, System.currentTimeMillis(), false) ?: 0 else 0
            if (!Schedule.isRecurring(j.schedule)) j.enabled = false
        }
        rt.scope.launch(Dispatchers.IO) {
            try {
                val bot = job.botId?.let { rt.bots.get(it) }
                val session = Session(source = "cron", title = "⏰ ${job.name}", chatKey = job.origin, botId = bot?.id)
                val agent = if (bot != null) rt.botHub.agentFor(bot, session, "cron") else Agent(rt, session, "cron")
                val skillText = job.skills.mapNotNull { rt.skills.find(it) }.joinToString("\n\n") { s ->
                    "[Loaded skill: ${s.name}]\n" + s.file.readText()
                }
                val prompt = buildString {
                    append("[Scheduled task '${job.name}' (job ${job.id}) is running now.]\n\n")
                    if (skillText.isNotBlank()) append(skillText).append("\n\n")
                    append(job.prompt)
                }
                val result = agent.runTurn(prompt, emptyList(), object : AgentListener {})
                rt.cron.update(job.id) { j ->
                    j.lastRunAt = System.currentTimeMillis(); j.runCount++; j.lastStatus = "ok"; j.lastOutput = result
                    if (j.repeat > 0 && j.runCount >= j.repeat) j.enabled = false
                }
                deliver(job, "⏰ ${job.name}\n\n$result")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                rt.cron.update(job.id) { j -> j.lastRunAt = System.currentTimeMillis(); j.lastStatus = "error: ${e.message}" }
                deliver(job, "⏰ ${job.name} failed: ${e.message}")
            } finally {
                running.remove(job.id)
            }
        }
    }

    private suspend fun deliver(job: CronJob, text: String) {
        val target = when (job.deliver) {
            "origin", "" -> job.origin ?: "local"
            else -> job.deliver
        }
        val who = job.botId?.let { rt.bots.get(it)?.name } ?: "Hermes"
        if (target == "local" || target.startsWith("app") || target.startsWith("terminal")) {
            rt.notify("$who · ${job.name}", text)
            return
        }
        rt.gateway.sendTo(target, text).onFailure { rt.notify("$who · ${job.name}", text) }
    }
}
