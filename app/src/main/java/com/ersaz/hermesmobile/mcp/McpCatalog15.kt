package com.ersaz.hermesmobile.mcp

import java.io.File

/**
 * Default MCP catalog: 15 servers as configuration entries ONLY
 * (Hermes' own code/UI; no server software is bundled or copied).
 * Each entry carries its real package/command/transport/auth as
 * verified from its own docs — or is marked UNVERIFIED where it could
 * not be verified in this pass. Unverified entries stay disabled
 * (connected=false) until checked; nothing is invented.
 *
 * Seeding rules (same as the Hub defaults): a new marker file, never
 * resurrecting a user-deleted entry, never overwriting user edits.
 * Enabled by default ONLY where no key/account/desktop is needed:
 * serena and n8n-mcp (docs mode) run without keys; everything needing
 * a key, an account, a URL, a database, or a desktop app stays
 * disabled with its "needs …" state until the user provides it.
 * Context7 and Framelink are already seeded elsewhere (CodingPack /
 * Hub defaults) and are NOT duplicated here — they count toward the
 * 15 (see the report table).
 */
object McpCatalog15 {
    data class Entry(
        val id: String, val displayName: String,
        val command: String? = null, val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val url: String? = null, val headers: Map<String, String> = emptyMap(),
        val note: String, val verified: Boolean,
        val enabled: Boolean = false, val autoStart: Boolean = false,
    )

    val ENTRIES = listOf(
        // 1. Context7 — already seeded by CodingPack (remote http,
        //    https://mcp.context7.com/mcp, CONTEXT7_API_KEY). Not duplicated.
        // 2. Chrome DevTools MCP — documented package, needs desktop Chrome.
        Entry("chrome-devtools", "Chrome DevTools MCP",
            command = "npx", args = listOf("chrome-devtools-mcp@latest"),
            note = "Browser debugging via the documented chrome-devtools-mcp package. NEEDS DESKTOP CHROME: it cannot drive a desktop browser from a phone. On-phone, use the built-in Browser tools row instead; this entry stays off unless you bridge a desktop Chrome.",
            verified = true),
        // 3. Codebase Memory MCP — package/command NOT verified here.
        Entry("codebase-memory", "Codebase Memory MCP",
            note = "UNVERIFIED — needs checking: package/command could not be verified from the project's docs in this pass. Entry exists so you can fill in the documented command; it stays disconnected until then. No command invented.",
            verified = false),
        // 4. Playwright MCP — phone equivalent is the built-in Browser tools.
        Entry("playwright-mcp", "Playwright MCP (wire entry)",
            command = "npx", args = listOf("@playwright/mcp@latest"),
            note = "Browser automation. PHONE EQUIVALENT: the built-in Browser tools row (Hermes' own BrowserManager implementation) is the active Playwright-style implementation and is enabled by default. This wire entry (documented @playwright/mcp package) needs a desktop browser and stays off on-phone unless you bridge one. Playwright itself is never bundled.",
            verified = true),
        // 5. GitHub MCP Server — command NOT verified here (needs token either way).
        Entry("github-mcp", "GitHub MCP Server",
            env = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "\${GITHUB_TOKEN}"),
            note = "UNVERIFIED — needs checking: the exact server command was not verified from the project's docs in this pass, and it needs YOUR OWN GitHub token (Settings → Environment / GitHub identity) in any case. The read-only GitHub default (github-readonly, already seeded) covers read-only work until then. No command invented.",
            verified = false),
        // 6. Context Mode — license unclear, entry only, no code copied.
        Entry("context-mode", "Context Mode",
            note = "UNVERIFIED — needs checking: package/command could not be verified from the project's docs in this pass, and its license is unclear — so this is a configuration entry only, no code copied. It stays disconnected until checked.",
            verified = false),
        // 7. Blender MCP — desktop Blender + phone script equivalent.
        Entry("blender-mcp", "Blender MCP",
            note = "NEEDS DESKTOP BLENDER: live Blender control cannot run on Android (no Blender for Android). Entry supports a remote/desktop-bridge URL once you fill it in; until then use the phone equivalent — the blender-scripts skill, which writes Blender Python scripts for desktop Blender. No connection is claimed on-phone. [unverified: no bridge command written]",
            verified = false),
        // 8. Code Review Graph — package/command NOT verified here.
        Entry("code-review-graph", "Code Review Graph",
            note = "UNVERIFIED — needs checking: package/command could not be verified from the project's docs in this pass. Entry exists so you can fill in the documented command; it stays disconnected until then. No command invented.",
            verified = false),
        // 9. Serena — verified: uvx --from git+https://github.com/oraios/serena serena start-mcp-server.
        Entry("serena", "Serena",
            command = "uvx", args = listOf("--from", "git+https://github.com/oraios/serena", "serena", "start-mcp-server", "--context", "ide-assistant", "--project", "/root/projects"),
            note = "Coding assistant (semantic code retrieval/editing via language servers). Verified command (uvx from oraios/serena). Runs in the sandbox where uv is installable; needs no key, so it is enabled by default. If uv/sandbox setup fails, it shows its real error.",
            verified = true, enabled = true, autoStart = true),
        // 10. n8n-MCP — verified: npx n8n-mcp, MCP_MODE=stdio; docs mode needs no key.
        Entry("n8n-mcp", "n8n MCP",
            command = "npx", args = listOf("n8n-mcp"),
            env = mapOf("MCP_MODE" to "stdio", "N8N_API_URL" to "\${N8N_API_URL}", "N8N_API_KEY" to "\${N8N_API_KEY}"),
            note = "n8n workflow knowledge (docs + validation; workflow management only with your own instance). Verified command (npx n8n-mcp, MCP_MODE=stdio). Docs mode needs no key, so it is enabled by default; management tools stay off until N8N_API_URL + N8N_API_KEY are set in Environment. Never point it at production workflows directly.",
            verified = true, enabled = true, autoStart = true),
        // 11. MCP Toolbox for Databases — verified: toolbox binary, --stdio --tools-file.
        Entry("toolbox-databases", "MCP Toolbox for Databases",
            command = "toolbox", args = listOf("--stdio", "--tools-file", "tools.yaml"),
            note = "Google's database toolbox (verified binary + --stdio mode). NEEDS YOUR OWN DATABASE: configure tools.yaml per database (connection + credentials in Environment, never a shared database) and place the toolbox binary where the sandbox finds it; until then this entry stays off with its real error. No database is bundled or shared.",
            verified = true),
        // 12. Xiaohongshu MCP — auth NOT verified as safe; cookies never extracted.
        Entry("xiaohongshu-mcp", "Xiaohongshu MCP",
            note = "NEEDS MANUAL SETUP — not connected: no safe auth was verified for this entry. Hermes NEVER extracts account cookies. Only a user-entered key/cookie field documented by the server itself may be used, with a warning that sharing cookies is risky; until such a documented field exists, this entry stays off.",
            verified = false),
        // 13. Figma Context MCP (Framelink) — already seeded by Hub defaults. Not duplicated.
        // 14. Unity MCP — needs Unity editor on desktop.
        Entry("unity-mcp", "Unity MCP",
            note = "NEEDS DESKTOP UNITY: the Unity editor cannot run on Android. Entry supports a remote/desktop-bridge URL once you fill it in (the well-known server is CoplayDev/unity-mcp — verify the exact bridge command there before connecting). No Unity connection is claimed on-phone. [unverified: no bridge command written]",
            verified = false),
        // 15. Apify MCP — token NOT verified as a command here.
        Entry("apify-mcp", "Apify MCP",
            note = "UNVERIFIED — needs checking: the exact server command was not verified from the project's docs in this pass, and it needs YOUR OWN Apify token in any case. Entry exists so you can fill in the documented command + token; it stays disconnected until then. No command or token invented.",
            verified = false),
    )

    /** Seeds missing entries once (marker-guarded, never resurrecting). */
    fun seed(store: McpStore, hermesHome: File): Int {
        val marker = File(hermesHome, "mcp/.catalog15-v1")
        var added = 0
        if (!marker.exists()) {
            ENTRIES.forEach { e ->
                if (store.get(e.id) == null) {
                    store.upsert(McpServer(
                        id = e.id, displayName = e.displayName,
                        command = e.command, args = e.args, env = e.env,
                        url = e.url, headers = e.headers,
                        enabled = e.enabled, autoStart = e.autoStart, builtin = true,
                        note = e.note + if (e.verified) "" else " [unverified]",
                    ))
                    added++
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        }
        return added
    }
}
