package com.ersaz.hermesmobile.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.jobs.SubAgentLimits
import com.ersaz.hermesmobile.agent.MemoryStore
import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.SkillStore
import com.ersaz.hermesmobile.agent.TodoItem
import com.ersaz.hermesmobile.agent.ToolCall
import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.strings
import com.ersaz.hermesmobile.cron.CronJob
import com.ersaz.hermesmobile.cron.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- memory

class MemoryTool : Tool("memory", "memory", MemoryStore.SCHEMA.getString("description"), "🧠") {
    override val parameters: JSONObject = MemoryStore.SCHEMA.getJSONObject("parameters")

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val target = args.optString("target", "memory").let { if (it == "user") "user" else "memory" }
        if (target == "memory" && !ctx.runtime.settings.memoryEnabled) return fail("Agent notes memory is disabled; use target='user'.")
        if (target == "user" && !ctx.runtime.settings.userProfileEnabled) return fail("User profile memory is disabled.")
        return ctx.agent.memory.apply(target, MemoryStore.opsFrom(args)).toString()
    }
}

// ---------------------------------------------------------------- skills

class SkillsListTool : Tool("skills_list", "skills", "List available skills (name, category, description).", "📚") {
    override val parameters = obj("category" to str("Optional category filter"))
    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val cat = args.optString("category")
        val list = ctx.runtime.skills.list().filter { cat.isBlank() || it.category.equals(cat, true) }
        return JSONObject().put("skills", JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("name", it.name).put("category", it.category).put("description", it.description)) }
        }).put("count", list.size).toString()
    }
}

class SkillViewTool : Tool(
    "skill_view", "skills",
    "Load a skill's SKILL.md instructions (or one of its linked files via file_path). Follow loaded instructions.",
    "📖",
) {
    override val parameters = obj(
        "name" to str("Skill name"),
        "file_path" to str("Optional file inside the skill dir, e.g. references/api.md"),
        required = listOf("name"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val skill = ctx.runtime.skills.find(args.optString("name")) ?: return@withContext fail("Skill not found: ${args.optString("name")}")
        val fp = args.optString("file_path")
        if (fp.isNotBlank()) {
            val f = File(skill.dir, fp).normalize()
            if (!f.path.startsWith(skill.dir.path) || !f.exists()) return@withContext fail("File not found in skill: $fp")
            return@withContext ok("name" to skill.name, "file" to fp, "content" to f.readText().take(60_000))
        }
        ok("name" to skill.name, "category" to skill.category, "path" to ctx.runtime.sandbox.guestPath(skill.dir),
            "content" to skill.file.readText(), "linked_files" to JSONArray(ctx.runtime.skills.linkedFiles(skill)))
    }
}

class SkillManageTool : Tool(
    "skill_manage", "skills",
    "Create and maintain skills (procedural memory). Actions: create (new SKILL.md with frontmatter name/description), " +
        "patch (exact string replace in SKILL.md or a linked file), edit (rewrite SKILL.md), delete, write_file / remove_file " +
        "(supporting files under references/, scripts/, templates/, assets/).",
    "🛠️",
) {
    override val parameters = obj(
        "action" to str("Action", listOf("create", "patch", "edit", "delete", "write_file", "remove_file")),
        "name" to str("Skill name (lowercase, hyphens)"),
        "content" to str("Full SKILL.md content for create/edit (include --- frontmatter with name and description)"),
        "category" to str("Category folder for create (default general)"),
        "old_string" to str("For patch: exact text to replace"),
        "new_string" to str("For patch: replacement"),
        "file_path" to str("For write_file/remove_file/patch: path relative to the skill dir"),
        "file_content" to str("For write_file: content"),
        required = listOf("action", "name"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val store = ctx.runtime.skills
        val name = args.optString("name")
        when (args.optString("action")) {
            "create" -> {
                if (store.find(name) != null) return@withContext fail("Skill '$name' exists; use patch or edit.")
                val content = args.optString("content")
                if (content.isBlank()) return@withContext fail("content is required")
                val s = store.create(name, content, args.optString("category", "general"))
                ok("created" to s.name, "path" to ctx.runtime.sandbox.guestPath(s.dir))
            }
            else -> {
                val skill = store.find(name) ?: return@withContext fail("Skill not found: $name")
                when (args.optString("action")) {
                    "edit" -> { skill.file.writeText(args.optString("content")); ok("edited" to skill.name) }
                    "delete" -> { store.delete(skill); ok("deleted" to skill.name) }
                    "patch" -> {
                        val target = args.optString("file_path").ifBlank { "SKILL.md" }.let { File(skill.dir, it) }
                        if (!target.exists()) return@withContext fail("File not found")
                        val text = target.readText()
                        val old = args.optString("old_string")
                        if (old.isEmpty() || !text.contains(old)) return@withContext fail("old_string not found")
                        target.writeText(text.replaceFirst(old, args.optString("new_string")))
                        ok("patched" to skill.name)
                    }
                    "write_file" -> {
                        val f = File(skill.dir, args.optString("file_path")).normalize()
                        if (!f.path.startsWith(skill.dir.path)) return@withContext fail("Path escapes skill dir")
                        f.parentFile?.mkdirs(); f.writeText(args.optString("file_content")); ok("written" to args.optString("file_path"))
                    }
                    "remove_file" -> {
                        val f = File(skill.dir, args.optString("file_path")).normalize()
                        if (!f.path.startsWith(skill.dir.path) || f.name == "SKILL.md") return@withContext fail("Refusing to remove that file")
                        ok("removed" to f.delete())
                    }
                    else -> fail("Unknown action")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- todo

class TodoTool : Tool(
    "todo", "todo",
    "Manage the task list for the current session. Call with no args to read it. Pass todos (each {id, content, status}) " +
        "to write: merge=false replaces the list, merge=true updates matching ids. Status: pending, in_progress, completed, cancelled. " +
        "Use it for multi-step work (3+ steps); keep exactly one item in_progress.",
    "📋",
) {
    override val parameters = obj(
        "todos" to arr("Todo items", obj("id" to str("Id"), "content" to str("Task"), "status" to str("Status", listOf("pending", "in_progress", "completed", "cancelled")))),
        "merge" to bool("Merge into the existing list instead of replacing"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val list = ctx.session.todos
        args.optJSONArray("todos")?.objects()?.let { items ->
            val incoming = items.map { TodoItem(it.optString("id").ifBlank { (list.size + 1).toString() }, it.optString("content"), it.optString("status", "pending")) }
            if (!args.optBoolean("merge")) { list.clear(); list += incoming }
            else incoming.forEach { n ->
                val i = list.indexOfFirst { it.id == n.id }
                if (i >= 0) list[i] = list[i].copy(content = n.content.ifBlank { list[i].content }, status = n.status) else list += n
            }
            ctx.runtime.sessions.save(ctx.session)
        }
        return JSONObject().put("todos", JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("id", it.id).put("content", it.content).put("status", it.status)) }
        }).put("summary", "${list.count { it.status == "completed" }}/${list.size} completed").toString()
    }
}

// ---------------------------------------------------------------- session_search

class SessionSearchTool : Tool(
    "session_search", "session_search",
    "Search past conversations (all platforms) for relevant context. Returns matching sessions with snippets.",
    "🕰️",
) {
    override val parameters = obj("query" to str("Keywords to search for"), "limit" to int("Max sessions (default 5)"), required = listOf("query"))

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val hits = ctx.runtime.sessions.search(args.optString("query"), args.optInt("limit", 5).coerceIn(1, 20), ctx.session.id)
        JSONObject().put("results", JSONArray().also { a ->
            hits.forEach { h -> a.put(JSONObject().put("session_id", h.sessionId).put("title", h.title).put("snippets", JSONArray(h.snippets))) }
        }).put("count", hits.size).toString()
    }
}

// ---------------------------------------------------------------- cronjob

class CronjobTool : Tool(
    "cronjob", "cronjob",
    "Schedule tasks the agent runs unattended. Schedules: '30m' (once after delay), 'every 2h', cron '0 9 * * *', " +
        "or ISO time '2026-10-20T09:00'. deliver: 'origin' (this chat), 'local' (phone notification), a platform name " +
        "(its home channel), or 'platform:chat_id'. The prompt must be self-contained — the job starts with no chat history.",
    "⏰",
) {
    override val parameters = obj(
        "action" to str("Action", listOf("create", "list", "update", "pause", "resume", "remove", "run")),
        "job_id" to str("Job id (update/pause/resume/remove/run)"),
        "name" to str("Short job name"),
        "prompt" to str("Self-contained task prompt"),
        "schedule" to str("Schedule expression"),
        "deliver" to str("Where to deliver results"),
        "skills" to arr("Skills to load before running", str("skill name")),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val store = ctx.runtime.cron
        val id = args.optString("job_id")
        return when (args.optString("action")) {
            "create" -> {
                val schedule = args.optString("schedule")
                Schedule.validate(schedule)?.let { return fail(it) }
                val prompt = args.optString("prompt")
                if (prompt.isBlank()) return fail("prompt is required")
                val job = store.add(CronJob(
                    name = args.optString("name").ifBlank { prompt.take(40) }, prompt = prompt, schedule = schedule,
                    deliver = args.optString("deliver", "origin"), origin = ctx.session.chatKey ?: "local",
                    skills = args.optJSONArray("skills")?.strings() ?: emptyList(),
                    botId = ctx.agent.bot?.takeUnless { it.isDefault }?.id,
                ))
                ctx.runtime.ensureServiceForCron()
                ok("job" to job.describe())
            }
            "list" -> JSONObject().put("jobs", JSONArray(store.all().map { it.describe() })).toString()
            "update" -> store.update(id) { j ->
                args.optString("prompt").ifBlank { null }?.let { j.prompt = it }
                args.optString("name").ifBlank { null }?.let { j.name = it }
                args.optString("deliver").ifBlank { null }?.let { j.deliver = it }
                args.optString("schedule").ifBlank { null }?.let {
                    j.schedule = it; j.nextRunAt = Schedule.next(it, System.currentTimeMillis(), true) ?: 0
                }
            }?.let { ok("job" to it.describe()) } ?: fail("No job $id")
            "pause" -> store.update(id) { it.enabled = false }?.let { ok("paused" to it.id) } ?: fail("No job $id")
            "resume" -> store.update(id) { j ->
                j.enabled = true
                if (j.nextRunAt < System.currentTimeMillis()) j.nextRunAt = Schedule.next(j.schedule, System.currentTimeMillis(), true) ?: 0
            }?.let { ok("resumed" to it.id) } ?: fail("No job $id")
            "remove" -> if (store.remove(id)) ok("removed" to id) else fail("No job $id")
            "run" -> store.get(id)?.let { ctx.runtime.cronRunner.runNowAsync(it); ok("triggered" to it.id) } ?: fail("No job $id")
            else -> fail("Unknown action")
        }
    }
}

// ---------------------------------------------------------------- delegate_task (sub-agents)

class DelegateTool : Tool(
    "delegate_task", "delegation",
    "Delegate a self-contained task to a sub-agent — its own isolated context and tool loop in a hidden child session, running " +
        "concurrently with you — and inspect or stop the ones you started. `action` defaults to delegate.\n\n" +
        "DELEGATE work needing many rounds of exploration (reading many files or pages, trial and error), bulky output you only need a conclusion from, " +
        "or independent sub-problems that can run in parallel (several calls in one turn). DON'T delegate what you can finish in one or two tool calls, " +
        "what needs the user's confirmation midway, or work depending on nuances of this conversation. A sub-agent cannot see this conversation and has " +
        "no memory: write `task` as a complete brief — goal, constraints, where things are, exactly what to return. Only 3 run at once; extras queue.\n\n" +
        "wait=false (default) returns immediately with status=running and a job_id; the result arrives later as a NEW MESSAGE starting " +
        "\"[Background task …]\". End your turn when you have nothing else to do — never poll, never promise to report back.",
    "🔀",
) {
    override val parameters = obj(
        "action" to str("delegate (default) | status | steer | cancel | resume", listOf("delegate", "status", "steer", "cancel", "resume")),
        "task" to str("action=delegate: the complete self-contained brief."),
        "agent" to str("action=delegate: which sub-agent runs it (see the roster in the system prompt); omit for the general one."),
        "model_choice" to str("action=delegate, only for Auto agents. Default same_as_me. default_model = the user's main model when the task clearly needs more capability; sub_model = the user's light/cheap model for simple bounded work.",
            listOf("same_as_me", "default_model", "sub_model")),
        "context" to str("action=delegate: raw material to hand over verbatim (file excerpts, errors, paths). Appended to the task."),
        "max_minutes" to int("action=delegate: wall-clock budget (default 10, max 60). Partial work is returned on timeout."),
        "wait" to bool("action=delegate: false (default) = background. true = block until finished; only when your next step cannot proceed without it."),
        "toolsets" to arr("action=delegate: toolsets for the sub-agent (default: terminal, file, web, skills, todo, mcp, browser, device…)", str("toolset")),
        "job_id" to str("action=status/steer/cancel/resume: job id or prefix (status/resume: omit for all)."),
        "message" to str("action=steer: the correction, phrased as an instruction to the running sub-agent."),
        // Legacy shapes kept so older prompts keep working:
        "goal" to str("Alias of task."),
        "tasks" to arr("Parallel tasks (each {goal, context}); they run in the background together unless wait=true.", obj("goal" to str("Goal"), "context" to str("Context"))),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val jobs = ctx.runtime.agentJobs
        if (!jobs.enabled()) return fail("Sub-agents are turned off in Settings → Sub-agents.")
        val parent = ctx.agent
        return when (args.optString("action").ifBlank { "delegate" }) {
            "delegate" -> delegate(args, ctx)
            "status" -> {
                val list = if (args.optString("job_id").isBlank()) jobs.forSession(parent.session.id) else listOfNotNull(jobs.find(parent.session.id, args.optString("job_id")))
                JSONObject().put("jobs", JSONArray(list.map { it.toJson() })).toString()
            }
            "steer" -> {
                val j = jobs.find(parent.session.id, args.optString("job_id")) ?: return fail("No such job")
                if (args.optString("message").isBlank()) return fail("message is required")
                if (jobs.steer(j, args.optString("message"))) ok("steered" to j.id) else fail("Job ${j.id} is not running (${j.state.name.lowercase()}).")
            }
            "cancel" -> {
                val j = jobs.find(parent.session.id, args.optString("job_id")) ?: return fail("No such job")
                if (jobs.cancel(j)) ok("cancelling" to j.id) else fail("Job ${j.id} already finished.")
            }
            "resume" -> {
                val r = jobs.resume(parent, args.optString("job_id").ifBlank { args.optString("child_session_id") })
                if (r.isEmpty()) fail("Nothing to resume.") else JSONObject().put("resumed", JSONArray(r.map { it.toJson(false) })).toString()
            }
            else -> fail("Unknown action")
        }
    }

    private suspend fun delegate(args: JSONObject, ctx: ToolContext): String {
        val jobs = ctx.runtime.agentJobs
        if (ctx.agent.depth >= 1) return fail("Sub-agents can't delegate further.")
        val tasks: List<Triple<String, String?, String?>> = args.optJSONArray("tasks")?.objects()?.take(3)?.map {
            Triple(it.optString("goal").ifBlank { it.optString("task") }, it.optString("context").ifBlank { null }, null)
        } ?: listOf(Triple(args.optString("task").ifBlank { args.optString("goal") }, args.optString("context").ifBlank { null }, args.optString("agent").ifBlank { null }))
        if (tasks.all { it.first.isBlank() }) return fail("task is required")
        val wait = args.optBoolean("wait", false)
        val toolsets = args.optJSONArray("toolsets")?.strings()?.toSet()
        val started = tasks.filter { it.first.isNotBlank() }.map { (task, context, agent) ->
            jobs.start(ctx.agent, task, agent ?: args.optString("agent").ifBlank { null }, args.optString("model_choice").ifBlank { null }, context,
                args.optInt("max_minutes", SubAgentLimits.DEFAULT_MINUTES), toolsets, wait)
        }
        if (!wait) {
            ctx.listener.onStatus("🔀 started ${started.joinToString { "${it.agentName} (${it.id})" }}")
            return JSONObject().put("jobs", JSONArray(started.map { it.toJson(false) }))
                .put("note", "Running in the background. Results will arrive as new messages; keep going or end your turn.").toString()
        }
        started.forEach { ctx.listener.onStatus("🔀 sub-agent ${it.agentName} (${it.id}) running…") }
        started.forEach { jobs.await(it) }
        return JSONObject().put("results", JSONArray(started.map { it.toJson(true) })).toString()
    }
}

// ---------------------------------------------------------------- send_message

class SendMessageTool : Tool(
    "send_message", "messaging",
    "Send a message to a connected messaging platform. target: 'telegram' / 'discord' / 'slack' (home channel) or " +
        "'platform:chat_id'. Use action='list' to see connected platforms and home channels.",
    "📨",
) {
    override val parameters = obj(
        "action" to str("send or list", listOf("send", "list")),
        "target" to str("Destination"),
        "message" to str("Text to send"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val gw = ctx.runtime.gateway
        if (args.optString("action") == "list") return ok("platforms" to gw.describeTargets())
        val target = args.optString("target")
        val msg = args.optString("message")
        if (target.isBlank() || msg.isBlank()) return fail("target and message are required")
        return gw.sendTo(target, msg).fold({ ok("sent_to" to it) }, { fail(it.message ?: "send failed") })
    }
}

// ---------------------------------------------------------------- clarify

class ClarifyTool : Tool(
    "clarify", "clarify",
    "Ask the user a clarifying question when the request is genuinely ambiguous. Optionally offer choices. Blocks until they answer.",
    "❓",
) {
    override val parameters = obj(
        "question" to str("The question"),
        "choices" to arr("Optional answer choices (max 4)", str("choice")),
        required = listOf("question"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val answer = ctx.listener.clarify(args.optString("question"), args.optJSONArray("choices")?.strings()?.take(4) ?: emptyList())
            ?: return fail("No interactive user available — proceed with your best judgment and state the assumption.")
        return ok("answer" to answer)
    }
}

// ---------------------------------------------------------------- text_to_speech

class TextToSpeechTool : Tool(
    "text_to_speech", "tts",
    "Convert text to speech using the phone's TTS engine. The audio file is attached to your reply on messaging platforms and played in the app.",
    "🔊",
) {
    override val parameters = obj("text" to str("Text to speak"), "language" to str("Optional BCP-47 language, e.g. en-US, fa-IR"), required = listOf("text"))

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val path = ctx.runtime.speech.synthesize(args.optString("text"), args.optString("language")) ?: return fail("TTS engine unavailable")
        val delivered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.runtime.generatedFiles.capture(path) }
        ctx.listener.onMedia(delivered.absolutePath)
        return ok("file" to ctx.runtime.sandbox.guestPath(delivered), "note" to "Audio delivered to the user.")
    }
}

// ---------------------------------------------------------------- vision

class VisionTool : Tool(
    "vision_analyze", "vision",
    "Analyze an image (sandbox path or URL) with the vision-capable model and answer a question about it.",
    "👁️",
) {
    override val parameters = obj("image" to str("Image path in the sandbox or https URL"), "question" to str("What to look for"), required = listOf("image"))

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val src = args.optString("image").ifBlank { args.optString("image_url") }
        val url = if (src.startsWith("http") || src.startsWith("data:")) src else {
            val f = ctx.runtime.sandbox.hostPath(src, ctx.agent.cwd)
            if (!f.exists()) return@withContext fail("Image not found: $src")
            val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
            "data:$mime;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }
        val q = args.optString("question").ifBlank { "Describe this image in detail." }
        val r = ctx.runtime.llmClient().chat(ctx.agent.model(), listOf(Message("user", q, images = listOf(url))), null, stream = false)
        ok("analysis" to r.content)
    }
}

// ---------------------------------------------------------------- android device

class AndroidDeviceTool : Tool(
    "android_device", "device",
    "Use phone features: clipboard_get, clipboard_set, notify (title, text), open_url, share_text, battery, device_info, vibrate, list_apps, open_app (package), open_intent (any action/uri/mime).",
    "📱",
) {
    override val parameters = obj(
        "action" to str("Action", listOf("clipboard_get", "clipboard_set", "notify", "open_url", "share_text", "battery", "device_info", "vibrate", "list_apps", "open_app", "open_intent")),
        "package" to str("Package name for open_app, e.g. com.android.chrome"),
        "intent_action" to str("Android intent action for open_intent, e.g. android.intent.action.VIEW"),
        "mime" to str("Optional MIME type for open_intent"),
        "text" to str("Text for clipboard_set / notify / share_text"),
        "title" to str("Title for notify"),
        "url" to str("URL for open_url"),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.Main) {
        val c: Context = ctx.runtime.app
        when (args.optString("action")) {
            "clipboard_get" -> {
                val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ok("text" to (cm.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() ?: ""))
            }
            "clipboard_set" -> {
                val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Hermes", args.optString("text"))); ok()
            }
            "notify" -> { ctx.runtime.notify(args.optString("title", "Hermes"), args.optString("text")); ok() }
            "open_url" -> {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(args.optString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok()
            }
            "share_text" -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.optString("text"))
                c.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok()
            }
            "battery" -> {
                val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                ok("percent" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), "charging" to bm.isCharging)
            }
            "device_info" -> ok("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT, "abi" to Build.SUPPORTED_ABIS.joinToString())
            "vibrate" -> {
                @Suppress("DEPRECATION")
                val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(300)
                ok()
            }
            "list_apps" -> {
                val pm = c.packageManager
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(launch, 0).map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.sorted()
                ok("count" to apps.size, "apps" to apps.joinToString("\n"))
            }
            "open_app" -> {
                val i = c.packageManager.getLaunchIntentForPackage(args.optString("package"))
                    ?: return@withContext fail("App not installed: ${args.optString("package")}")
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok()
            }
            "open_intent" -> {
                val i = Intent(args.optString("intent_action", Intent.ACTION_VIEW))
                val uri = args.optString("url")
                val mime = args.optString("mime")
                when {
                    uri.isNotBlank() && mime.isNotBlank() -> i.setDataAndType(Uri.parse(uri), mime)
                    uri.isNotBlank() -> i.data = Uri.parse(uri)
                    mime.isNotBlank() -> i.type = mime
                }
                if (args.optString("text").isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, args.optString("text"))
                try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok() } catch (e: Exception) { fail("No app can handle it: ${e.message}") }
            }
            else -> fail("Unknown action")
        }
    }
}

// ---------------------------------------------------------------- message_bot (Bot Mode)

class MessageBotTool : Tool(
    "message_bot", "bots",
    "Hand a message or task to another bot in the roster (Bot Mode). It arrives as \"Message from 🤖 <you> (@<you>): …\" in that bot's " +
        "persistent Bot Chat; the tool waits for its reply and returns it. Use when the user @mentions a bot or the task suits another bot's specialty. " +
        "Use action='list' to see the roster. Give the bot all the context it needs: it does not see this conversation.",
    "🤖",
) {
    override val parameters = obj(
        "action" to str("send or list", listOf("send", "list")),
        "bot" to str("Bot id or name, with or without @"),
        "message" to str("What to tell it"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val rt = ctx.runtime
        if (args.optString("action") == "list") {
            return ok("bots" to JSONArray(rt.bots.all().filter { it.id != ctx.agent.botId }.map {
                JSONObject().put("id", it.id).put("name", it.name).put("title", it.title).put("description", it.description)
            }))
        }
        val target = rt.bots.get(args.optString("bot")) ?: return fail("No bot '${args.optString("bot")}'. Use action='list'.")
        val message = args.optString("message")
        if (message.isBlank()) return fail("message is required")
        if (target.id == ctx.agent.botId) return fail("You can't message yourself.")
        val from = rt.bots.get(ctx.agent.botId)
        ctx.listener.onStatus("🤖 → ${target.name}: ${message.take(80)}")
        val chain = ctx.agent.botChain.ifEmpty { listOf(ctx.agent.botId) }
        return rt.botHub.deliver(from, target, message, chain).fold(
            { ok("from" to target.id, "reply" to it) },
            { fail(it.message ?: "delivery failed") },
        )
    }
}
