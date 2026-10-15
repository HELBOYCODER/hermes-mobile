package com.ersaz.hermesmobile.coding

import java.io.File

enum class CodingPhase { CONSTITUTION, SPECIFY, PLAN, TASKS, IMPLEMENT, CONVERGE }

/** Adapts the pinned Spec Kit templates; existing project files always win. */
class SpecWorkflow(private val templates: Map<CodingPhase, String> = emptyMap(), private val feature: String = "001-feature") {
    init { require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(feature)) }
    fun initialize(projectRoot: File): List<File> = listOfNotNull(
        create(projectRoot, ".specify/memory/constitution.md", templates[CodingPhase.CONSTITUTION]
            ?: "# Project constitution\n\nPreserve user changes. Specify acceptance criteria. Verify changes with executable tests.\n"))
    fun advance(projectRoot: File, phase: CodingPhase): File {
        initialize(projectRoot)
        val relative = if (phase == CodingPhase.CONSTITUTION) ".specify/memory/constitution.md" else
            "specs/$feature/" + when (phase) {
                CodingPhase.SPECIFY -> "spec.md"
                CodingPhase.PLAN -> "plan.md"
                CodingPhase.TASKS -> "tasks.md"
                CodingPhase.IMPLEMENT -> "implementation.md"
                else -> "verification.md"
            }
        val body = templates[phase] ?: when (phase) {
            CodingPhase.SPECIFY -> "# Feature specification\n\n## Goal\n\n## Constraints\n\n## Acceptance criteria\n"
            CodingPhase.TASKS -> "# Tasks\n\n- [ ] Describe a testable task\n"
            CodingPhase.CONVERGE -> "# Verification\n\n## Commands and actual results\n\n## Unresolved issues\n"
            else -> "# ${phase.name.lowercase()}\n\nRecord decisions, changed files and test evidence here.\n"
        }
        return create(projectRoot, relative, body) ?: resolveProjectFile(projectRoot, relative)
    }
    private fun create(root: File, path: String, body: String): File? {
        val file = resolveProjectFile(root, path)
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) return null
        file.writeText(body)
        return file
    }
}

fun resolveProjectFile(root: File, relative: String): File {
    require(!File(relative).isAbsolute && relative.isNotBlank()) { "Use a project-relative path" }
    val base = root.canonicalFile
    val file = File(base, relative).canonicalFile
    require(file.path.startsWith(base.path + File.separator)) { "Path escapes the project" }
    require(file.relativeTo(base).invariantSeparatorsPath.split('/').none { it == ".git" }) { "Git metadata is not a project file" }
    return file
}
