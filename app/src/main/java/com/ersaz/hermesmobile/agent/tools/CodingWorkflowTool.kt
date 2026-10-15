package com.ersaz.hermesmobile.agent.tools

import com.ersaz.hermesmobile.coding.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class CodingWorkflowTool : Tool("coding_workflow", "file",
    "Manage the selected project's specification, persistent tasks and actual test evidence. Actions: status, phase, task, run_test. run_test uses the existing terminal and approval gate. Task completion requires run_id from a passing saved test; inspect whether that test proves the acceptance criteria.", "🛠️") {
    override val parameters = obj("action" to str("status | phase | task | run_test"),
        "phase" to str("constitution | specify | plan | tasks | implement | converge"),
        "feature" to str("Feature id, default 001-feature"), "id" to str("Task id"), "text" to str("Task description"),
        "status" to str("todo | doing | done"), "run_id" to str("Actual passing test evidence id"),
        "command" to str("Test command"), required = listOf("action"))
    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val root = projectRoot(ctx.runtime)
        val tasks = projectTaskStore(ctx.runtime, root)
        when (args.optString("action")) {
            "status" -> tasks.read().toString()
            "phase" -> {
                val phase = CodingPhase.valueOf(args.optString("phase").uppercase())
                val file = specWorkflow(ctx.runtime, args.optString("feature", "001-feature")).advance(root, phase)
                ok("artifact" to ctx.runtime.sandbox.guestPath(file), "phase" to phase.name, "existing_content_preserved" to true)
            }
            "task" -> {
                tasks.update(args.getString("id"), args.getString("text"), args.getString("status"), args.optString("run_id").ifBlank { null })
                tasks.read().toString()
            }
            "run_test" -> {
                if (ctx.agent.tools().none { it.name == "terminal" }) return@withContext fail("Enable the terminal tool before running project tests")
                val command = args.getString("command").trim(); require(command.isNotEmpty() && command.length <= 4000)
                val id = UUID.randomUUID().toString()
                tasks.recordRun(id, null, "RUNNING", command)
                try {
                    val raw = TerminalTool().run(JSONObject().put("command", command).put("workdir", ctx.runtime.sandbox.guestPath(root)).put("timeout", 180), ctx)
                    val result = JSONObject(raw)
                    val exit = if (result.has("exit_code")) result.getInt("exit_code") else null
                    val status = when {
                        result.optString("error").contains("timed out", true) -> "TIMED_OUT"
                        exit == null -> "NOT_RUN"
                        exit == 0 && !result.has("error") -> "PASSED"
                        else -> "FAILED"
                    }
                    val redacted = redact(ctx, result.optString("output", result.optString("error")))
                    tasks.recordRun(id, exit, status, redact(ctx, command), redacted)
                    JSONObject().put("run_id", id).put("status", status).put("exit_code", exit ?: JSONObject.NULL)
                        .put("output", redacted.take(32000)).put("output_truncated", redacted.length > 32000).toString()
                } catch (e: CancellationException) { tasks.recordRun(id, null, "CANCELLED", redact(ctx, command)); throw e }
                catch (e: Exception) { tasks.recordRun(id, null, "NOT_RUN", redact(ctx, command)); fail("Test could not run; inspect project/runtime configuration") }
            }
            else -> fail("Unknown coding action")
        }
    }
    private fun redact(ctx: ToolContext, text: String): String {
        var safe = ctx.runtime.settings.redactEnvironment(text)
        (ctx.runtime.modelStore.all().map { it.apiKey } + ctx.runtime.settings.apiKey).filter { it.isNotBlank() }.forEach { safe = safe.replace(it, "[REDACTED]") }
        return safe
    }
}
