package com.ersaz.hermesmobile.mcp

import com.ersaz.hermesmobile.agent.tools.Tool
import com.ersaz.hermesmobile.agent.tools.ToolContext
import com.ersaz.hermesmobile.agent.tools.fail
import com.ersaz.hermesmobile.agent.tools.obj
import com.ersaz.hermesmobile.agent.tools.str
import com.ersaz.hermesmobile.core.parseArgs
import org.json.JSONArray
import org.json.JSONObject

/** One MCP tool exposed to the model as a regular function. */
class McpTool(private val mgr: McpManager, private val def: McpToolDef, wire: String) :
    Tool(wire, "mcp", "[MCP:${def.server}] ${def.description.ifBlank { def.name }}".take(900), "🔌") {
    override val parameters: JSONObject = def.schema
    override suspend fun run(args: JSONObject, ctx: ToolContext): String = mgr.callTool(def.server, def.name, args)
    override fun preview(args: JSONObject): String =
        args.keys().asSequence().firstOrNull { args.opt(it) is String }?.let { args.optString(it).replace('\n', ' ').take(90) } ?: def.name
}

/** Browse and call any MCP tool, including ones beyond the native cap. */
class McpGenericTool(private val mgr: McpManager) : Tool(
    "mcp", "mcp",
    "Work with connected MCP servers. action=servers lists them; action=tools [server] [query] lists tool names and descriptions; " +
        "action=call runs {server, tool, arguments}. Prefer the dedicated mcp__* functions when they exist.",
    "🔌",
) {
    override val parameters = obj(
        "action" to str("servers | tools | call", listOf("servers", "tools", "call")),
        "server" to str("Server id"),
        "tool" to str("Tool name (call)"),
        "query" to str("Filter for action=tools"),
        "arguments" to JSONObject().put("type", "object").put("description", "Arguments for the tool (call)"),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        return when (args.optString("action")) {
            "servers" -> JSONObject().put("servers", JSONArray(mgr.servers().map { s ->
                JSONObject().put("id", s.id).put("enabled", s.enabled).put("status", describe(mgr.statusOf(s.id))).put("note", s.note)
            })).toString()
            "tools" -> {
                val q = args.optString("query").lowercase()
                val server = args.optString("server")
                JSONObject().put("tools", JSONArray(mgr.allDefs()
                    .filter { (server.isBlank() || it.server.equals(server, true)) && (q.isBlank() || q in it.name.lowercase() || q in it.description.lowercase()) }
                    .take(80).map { JSONObject().put("server", it.server).put("tool", it.name).put("description", it.description.take(200))
                        .put("parameters", it.schema.optJSONObject("properties")?.keys()?.asSequence()?.toList()?.let(::JSONArray) ?: JSONArray()) })).toString()
            }
            "call" -> {
                val server = args.optString("server"); val tool = args.optString("tool")
                if (server.isBlank() || tool.isBlank()) return fail("server and tool are required")
                val a = args.optJSONObject("arguments") ?: parseArgs(args.optString("arguments"))
                mgr.callTool(server, tool, a)
            }
            else -> fail("Unknown action")
        }
    }

    private fun describe(s: McpStatus?) = when (s) {
        is McpStatus.Connected -> "connected (${s.tools} tools)"
        is McpStatus.NeedsAuth -> "needs sign-in"
        is McpStatus.Failed -> "failed: ${s.message.take(120)}"
        is McpStatus.Connecting -> "connecting"
        McpStatus.Disabled -> "disabled"
        null -> "unknown"
    }
}
