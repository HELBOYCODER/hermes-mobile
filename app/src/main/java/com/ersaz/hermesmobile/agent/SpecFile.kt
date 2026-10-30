package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import java.io.File

/**
 * Living SPEC.md (ThinkRail concept, Hermes implementation — no
 * ThinkRail code or UI copied, and the desktop ThinkRail IDE is NOT
 * claimed to run on Android).
 *
 * Every project gets SPEC.md in its root. It is created from the
 * already-bundled Spec Kit spec template (assets/coding/spec-kit,
 * the same templates ProjectWorkspace reads) plus the user's stated
 * intent. The agent MUST read it first at the start of every session
 * (see the spec-first routing in PromptBuilder) and update it after
 * each completed task, so intent survives across sessions. SPEC.md
 * lives in the project folder, so it travels with ZIP export and
 * git — not only in chat memory.
 */
object SpecFile {
    fun guestPath(projectGuestDir: String) = "$projectGuestDir/SPEC.md"

    private fun hostFile(rt: HermesRuntime, projectGuestDir: String): File =
        runCatching { rt.sandbox.hostPath(guestPath(projectGuestDir)) }.getOrNull()
            ?: File(projectGuestDir, "SPEC.md")

    fun read(rt: HermesRuntime, projectGuestDir: String): String? =
        runCatching { hostFile(rt, projectGuestDir).takeIf { it.isFile }?.readText() }.getOrNull()

    /** Create SPEC.md from the bundled Spec Kit spec template + intent. */
    fun ensure(rt: HermesRuntime, projectGuestDir: String, intent: String): String {
        val f = hostFile(rt, projectGuestDir)
        if (f.isFile) return "SPEC.md already exists (${f.length()} bytes)."
        val template = runCatching {
            rt.app.assets.open("coding/spec-kit/spec-template.md").bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        val body = buildString {
            append("# SPEC.md — living project spec\n\n")
            append("Intent (user's stated goal):\n$intent\n\n")
            append("## Decisions\n\n(none yet — updated after each completed task)\n\n")
            append("## Architecture notes\n\n(none yet)\n\n")
            append("## Tasks\n\n- [ ] (filled by the agent from the plan)\n\n")
            append("## Session notes\n\n")
            append("Created by Hermes from the bundled Spec Kit spec template. ")
            append("The agent reads this file first every session and updates it after each task.\n")
            if (template.isNotBlank()) {
                append("\n---\nSpec Kit template reference (bundled, truncated):\n")
                append(template.take(2000))
            }
        }
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(body)
            "SPEC.md created in $projectGuestDir."
        }.getOrElse { "Could not write SPEC.md: ${it.message}" }
    }

    /** Append a completed-task note (intent, decisions, architecture). */
    fun noteCompleted(rt: HermesRuntime, projectGuestDir: String, task: String, decisions: String): String {
        val f = hostFile(rt, projectGuestDir)
        if (!f.isFile) ensure(rt, projectGuestDir, "(project started before SPEC.md existed)")
        return runCatching {
            f.appendText("\n## Completed: $task\n$decisions\n")
            "SPEC.md updated with: $task"
        }.getOrElse { "Could not update SPEC.md: ${it.message}" }
    }
}
