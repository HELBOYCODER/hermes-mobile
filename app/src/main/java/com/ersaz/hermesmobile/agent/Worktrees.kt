package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import org.json.JSONObject

/**
 * Worktree per task + one-click PR/CI (ThinkRail concepts, Hermes
 * implementation on sandbox git — no ThinkRail code/UI copied).
 *
 * Each agent task runs in its own git worktree with its own branch
 * under /root/projects/.worktrees/<task>, so parallel agents never
 * touch the user's working copy. Merge stops honestly on conflicts
 * (no auto-resolution, conflict report shown). PR/CI use the user's
 * own GitHub token from Settings (githubToken) and the project's own
 * git remote; with no remote or no token the buttons say exactly
 * that instead of faking a PR. No ThinkRail account exists and
 * neither does one here: everything runs on the user's own provider
 * credentials already in Hermes.
 */
object Worktrees {
    data class Entry(val path: String, val branch: String, val head: String)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun exec(rt: HermesRuntime, cmd: String, timeoutSec: Int = 300): Pair<Int, String> {
        val r = runCatching { rt.sandbox.exec(cmd, timeoutSec = timeoutSec) }.getOrNull()
        return if (r == null) 1 to "sandbox exec unavailable" else r.exitCode to r.output
    }

    fun safeTask(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(60).ifBlank { "task" }

    suspend fun list(rt: HermesRuntime, project: String): List<Entry> {
        val (code, out) = exec(rt, "git -C ${q(project)} worktree list --porcelain")
        if (code != 0) return emptyList()
        val entries = ArrayList<Entry>()
        var path = ""; var branch = ""; var head = ""
        for (line in out.lines()) {
            when {
                line.startsWith("worktree ") -> { if (path.isNotEmpty()) entries += Entry(path, branch, head); path = line.removePrefix("worktree ").trim(); branch = ""; head = "" }
                line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ").trim().take(12)
                line.startsWith("branch ") -> branch = line.removePrefix("branch ").trim().removePrefix("refs/heads/")
            }
        }
        if (path.isNotEmpty()) entries += Entry(path, branch, head)
        return entries
    }

    suspend fun create(rt: HermesRuntime, project: String, task: String): String {
        val safe = safeTask(task)
        val branch = "task/$safe"
        val dir = "/root/projects/.worktrees/$safe"
        val (code, out) = exec(rt, "git -C ${q(project)} worktree add ${q(dir)} -b ${q(branch)}")
        return if (code == 0) "Worktree created: $dir (branch $branch). SPEC.md travels with git once committed."
        else "Worktree failed: ${out.take(500)}"
    }

    /** Merge a worktree branch; on conflict, stop + report, never auto-resolve. */
    suspend fun merge(rt: HermesRuntime, project: String, branch: String): String {
        val (code, out) = exec(rt, "git -C ${q(project)} merge --no-ff ${q(branch)}")
        if (code == 0) return "Merged $branch into the main working copy."
        val (_, status) = exec(rt, "git -C ${q(project)} status --short")
        exec(rt, "git -C ${q(project)} merge --abort")
        return "Merge STOPPED on conflict in $branch — nothing was auto-resolved. " +
            "Conflicting files:\n${status.take(1000)}\nMerge output:\n${out.take(500)}"
    }

    suspend fun discard(rt: HermesRuntime, project: String, path: String, branch: String): String {
        val (code, out) = exec(rt, "git -C ${q(project)} worktree remove --force ${q(path)}")
        if (code != 0) return "Discard failed: ${out.take(500)}"
        if (branch.isNotBlank()) exec(rt, "git -C ${q(project)} branch -D ${q(branch)}")
        return "Worktree $path discarded (branch $branch deleted)."
    }

    private suspend fun remoteRepo(rt: HermesRuntime, project: String): Pair<String, String>? {
        val (code, out) = exec(rt, "git -C ${q(project)} remote get-url origin")
        if (code != 0) return null
        val url = out.trim()
        val m = Regex("github\\.com[:/]([^/]+)/([^/.]+)(\\.git)?").find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Push + open a PR via the GitHub API. Exact missing-state messages. */
    suspend fun createPr(rt: HermesRuntime, project: String, branch: String, title: String): String {
        val repo = remoteRepo(rt, project) ?: return "Cannot create a PR: this project has no git remote pointing at GitHub (git remote get-url origin found nothing usable)."
        val token = rt.settings.githubToken
        if (token.isBlank()) return "Cannot create a PR: no GitHub token in Settings (githubToken). Add your own token, then retry."
        val (owner, name) = repo
        val (pushCode, pushOut) = exec(rt, "git -C ${q(project)} push origin ${q(branch)}")
        if (pushCode != 0) return "Push failed: ${pushOut.take(500)}"
        val body = JSONObject().put("title", title).put("head", branch).put("base", "main")
            .put("body", "Created from Hermes Mobile (worktree branch $branch).").toString()
        val script = "curl -s -X POST -H ${q("Authorization: Bearer $token")} -H \"Accept: application/vnd.github+json\" " +
            "-d ${q(body)} https://api.github.com/repos/$owner/$name/pulls"
        val (code, out) = exec(rt, script)
        if (code != 0) return "PR request failed: ${out.take(500)}"
        val url = runCatching { JSONObject(out).optString("html_url") }.getOrNull().orEmpty()
        return if (url.isNotBlank()) "Pull request created: $url"
        else "GitHub did not return a PR URL: ${out.take(500)}"
    }

    /** Latest workflow run for a branch (existing GitHub API route). */
    suspend fun ciStatus(rt: HermesRuntime, project: String, branch: String): String {
        val repo = remoteRepo(rt, project) ?: return "CI status unavailable: this project has no GitHub remote."
        val (owner, name) = repo
        val token = rt.settings.githubToken
        val auth = if (token.isNotBlank()) "-H ${q("Authorization: Bearer $token")} " else ""
        val (code, out) = exec(rt, "curl -s ${auth}-H \"Accept: application/vnd.github+json\" " +
            "\"https://api.github.com/repos/$owner/$name/actions/runs?branch=$branch&per_page=1\"")
        if (code != 0) return "CI lookup failed: ${out.take(500)}"
        return runCatching {
            val runs = JSONObject(out).optJSONArray("workflow_runs")
            if (runs == null || runs.length() == 0) "No workflow runs found for branch $branch."
            else {
                val r = runs.getJSONObject(0)
                "CI for $branch: ${r.optString("name")} — status ${r.optString("status")}, conclusion ${r.optString("conclusion")} (${r.optString("html_url")})"
            }
        }.getOrElse { "Could not parse CI response: ${out.take(300)}" }
    }
}
