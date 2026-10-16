package com.ersaz.hermesmobile.agent

import android.content.Context
import java.io.File

/**
 * Skills in the agentskills.io / Hermes layout:
 *   $HERMES_HOME/skills/<category>/<name>/SKILL.md   (YAML frontmatter: name, description)
 *   optional references/, scripts/, assets/, templates/
 */
class SkillStore(val dir: File) {

    data class Skill(val name: String, val description: String, val category: String, val dir: File) {
        val file get() = File(dir, "SKILL.md")
    }

    init { dir.mkdirs() }

    fun list(): List<Skill> = dir.walkTopDown().maxDepth(4)
        .filter { it.isFile && it.name == "SKILL.md" }
        .mapNotNull { parse(it) }
        .sortedWith(compareBy({ it.category }, { it.name }))
        .toList()

    fun find(name: String): Skill? {
        val n = name.trim().lowercase()
        return list().firstOrNull { it.name.lowercase() == n || it.dir.name.lowercase() == n }
    }

    private fun parse(f: File): Skill? {
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val fm = frontmatter(text)
        val name = fm["name"]?.ifBlank { null } ?: f.parentFile!!.name
        val desc = fm["description"].orEmpty()
        val rel = f.parentFile!!.relativeTo(dir).path.split('/')
        val category = if (rel.size >= 2) rel.first() else "general"
        return Skill(name, desc, category, f.parentFile!!)
    }

    fun linkedFiles(skill: Skill): List<String> = skill.dir.walkTopDown()
        .filter { it.isFile && it.name != "SKILL.md" }
        .map { it.relativeTo(skill.dir).path }
        .toList()

    fun create(name: String, content: String, category: String = "general"): Skill {
        val safe = sanitize(name)
        val d = File(dir, "${sanitize(category).ifEmpty { "general" }}/$safe")
        d.mkdirs()
        val body = if (content.trimStart().startsWith("---")) content
        else "---\nname: $safe\ndescription: ${content.lineSequence().firstOrNull()?.take(120).orEmpty()}\n---\n\n$content"
        File(d, "SKILL.md").writeText(body)
        return parse(File(d, "SKILL.md"))!!
    }

    fun delete(skill: Skill) = skill.dir.deleteRecursively()

    fun indexBlock(): String? {
        val skills = list()
        if (skills.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("## Skills (mandatory)\n")
        sb.append("Before replying, scan the skills below. For substantial engineering, debugging or design tasks, load arena and use parallel candidate subagents when genuinely independent and safe. If a skill matches your task, load it with skill_view(name) and follow its instructions. ")
        sb.append("If a skill has issues, fix it with skill_manage(action='patch').\n\n<available_skills>\n")
        skills.groupBy { it.category }.forEach { (cat, list) ->
            sb.append("  $cat:\n")
            list.forEach { sb.append("    - ${it.name}: ${it.description.take(160)}\n") }
        }
        sb.append("</available_skills>")
        return sb.toString()
    }

    companion object {
        fun sanitize(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')

        fun frontmatter(text: String): Map<String, String> {
            if (!text.startsWith("---")) return emptyMap()
            val end = text.indexOf("\n---", 3)
            if (end < 0) return emptyMap()
            val map = mutableMapOf<String, String>()
            text.substring(3, end).lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0 && !line.startsWith(" ")) {
                    map[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"', '\'')
                }
            }
            return map
        }

        /** Copy bundled skills from assets/skills on first run (never overwrites user edits). */
        fun installBundled(context: Context, dest: File) {
            fun copy(assetPath: String, out: File) {
                val children = context.assets.list(assetPath) ?: return
                if (children.isEmpty()) {
                    if (!out.exists()) {
                        out.parentFile?.mkdirs()
                        context.assets.open(assetPath).use { i -> out.outputStream().use { i.copyTo(it) } }
                    }
                } else children.forEach { copy("$assetPath/$it", File(out, it)) }
            }
            copy("skills", dest)
        }
    }
}
