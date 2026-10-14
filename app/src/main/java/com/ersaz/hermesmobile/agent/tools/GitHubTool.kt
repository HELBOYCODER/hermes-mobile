package com.ersaz.hermesmobile.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Direct GitHub REST access using the token the user saved in Settings → GitHub. */
class GitHubTool : Tool(
    "github_api", "github",
    "Call the GitHub REST API as the user (repos, issues, PRs, releases, actions, gists, search…). " +
        "Authentication is already configured in Settings → GitHub. For git operations on code use the terminal " +
        "(git is pre-authenticated with the same token).",
    "🐙",
) {
    override val parameters = obj(
        "method" to str("HTTP method", listOf("GET", "POST", "PATCH", "PUT", "DELETE")),
        "path" to str("API path such as /user/repos or /repos/OWNER/REPO/issues (or a full api.github.com URL)"),
        "body" to str("JSON request body for POST/PATCH/PUT (as a JSON string)"),
        required = listOf("path"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val token = ctx.runtime.settings.githubToken
        if (token.isBlank()) return@withContext fail("GitHub is not connected. Ask the user to add a token in Settings → GitHub.")
        val path = args.optString("path").trim()
        val url = if (path.startsWith("http")) path else "https://api.github.com/" + path.trimStart('/')
        if (!url.startsWith("https://api.github.com/")) return@withContext fail("Only api.github.com URLs are allowed")
        val method = args.optString("method", "GET").uppercase()
        val bodyText = args.optString("body")
        val body = if (method == "GET" || method == "DELETE" && bodyText.isBlank()) null
        else bodyText.ifBlank { "{}" }.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "hermes-mobile")
            .build()
        try {
            ctx.runtime.http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                JSONObject().put("status", r.code).put("success", r.isSuccessful)
                    .put("body", if (text.length > 30_000) text.take(30_000) + "…(truncated)" else text).toString()
            }
        } catch (e: Exception) {
            fail("GitHub request failed: ${e.message}")
        }
    }
}
