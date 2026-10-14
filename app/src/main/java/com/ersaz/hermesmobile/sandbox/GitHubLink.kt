package com.ersaz.hermesmobile.sandbox

import com.ersaz.hermesmobile.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Connects the sandbox's git (and `gh`/API-using tools) to the user's GitHub account.
 * The token lives in [Settings]; this writes ~/.gitconfig and ~/.git-credentials inside the
 * rootfs so `git clone/push` works without any prompt, and exposes GH_TOKEN via [Sandbox].
 */
object GitHubLink {
    data class Account(val login: String, val name: String, val email: String)

    /** Verify the token against the GitHub API. */
    suspend fun verify(http: OkHttpClient, token: String): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "hermes-mobile")
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("GitHub said HTTP ${r.code}: ${body.take(120)}")
                val o = JSONObject(body)
                val login = o.optString("login")
                Account(login, o.optString("name").takeIf { it != "null" }.orEmpty().ifBlank { login },
                    o.optString("email").takeIf { it.isNotBlank() && it != "null" } ?: "$login@users.noreply.github.com")
            }
        }
    }

    /** Write git identity + credential store into the rootfs home. Safe to call repeatedly. */
    suspend fun apply(sandbox: Sandbox, s: Settings) = withContext(Dispatchers.IO) {
        val home = File(sandbox.rootfs, "root").apply { mkdirs() }
        val cred = File(home, ".git-credentials")
        val cfg = File(home, ".gitconfig")
        if (s.githubToken.isBlank()) {
            cred.delete()
            return@withContext
        }
        val user = s.githubUser.ifBlank { "x-access-token" }
        cred.writeText("https://${enc(user)}:${enc(s.githubToken)}@github.com\n")
        cred.setReadable(false, false); cred.setReadable(true, true)
        val name = s.githubName.ifBlank { s.githubUser.ifBlank { "Hermes" } }
        val email = s.githubEmail.ifBlank { "${s.githubUser.ifBlank { "hermes" }}@users.noreply.github.com" }
        cfg.writeText(
            """
            [user]
            	name = $name
            	email = $email
            [credential]
            	helper = store
            [init]
            	defaultBranch = main
            [safe]
            	directory = *
            """.trimIndent() + "\n"
        )
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}
