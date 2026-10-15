package com.ersaz.hermesmobile.coding

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.mcp.McpServer
import com.ersaz.hermesmobile.mcp.McpStore
import java.io.File

object CodingDefaults {
    const val SERVER_ID = "hermes-code"
    fun server() = McpServer(id = SERVER_ID, command = "python3",
        args = listOf("-u", "/root/.hermes/mcp/workspace_mcp.py", "--root", "/root/projects"),
        note = "Bundled read-only project overview, source search and Git changes. Projects live in /root/projects.")

    fun optionalServers() = listOf(
        McpServer(id = "context7-docs", url = "https://mcp.context7.com/mcp", transport = "http", enabled = false,
            headers = mapOf("Authorization" to "Bearer \${CONTEXT7_API_KEY}"),
            note = "Library documentation. Set CONTEXT7_API_KEY in Environment, then enable. Requires internet and a Context7 key."),
        McpServer(id = "github-readonly", url = "https://api.githubcopilot.com/mcp/readonly", transport = "http", enabled = false,
            headers = mapOf("Authorization" to "Bearer \${GITHUB_TOKEN}"),
            note = "Official GitHub MCP, read-only. Uses your GitHub token from Settings or GITHUB_TOKEN in Environment. Enable when ready.")
    )

    /** Seed once; never restore a server the user deliberately removed or disabled. */
    fun seed(store: McpStore, marker: File) {
        if (marker.exists()) return
        (listOf(server()) + optionalServers()).forEach { preset -> if (store.get(preset.id) == null) store.upsert(preset) }
        marker.parentFile?.mkdirs()
        marker.writeText("1")
    }
}

object CodingPack {
    fun install(rt: HermesRuntime) {
        File(rt.sandbox.rootfs, "root/projects").mkdirs()
        val script = File(rt.hermesHome, "mcp/workspace_mcp.py")
        script.parentFile?.mkdirs()
        val bytes = rt.app.assets.open("coding/workspace_mcp.py").use { it.readBytes() }
        if (!script.exists() || !script.readBytes().contentEquals(bytes)) {
            val temp = File(script.parentFile, "workspace_mcp.py.tmp")
            temp.writeBytes(bytes)
            check(temp.renameTo(script)) { "Could not install bundled coding MCP" }
        }
        CodingDefaults.seed(rt.mcp.store, File(rt.hermesHome, "mcp/.coding-defaults-v1"))
        if (rt.mcp.store.get(CodingDefaults.SERVER_ID)?.enabled == true) rt.mcp.reconnectAsync(CodingDefaults.SERVER_ID, interactive = false)
    }
}
