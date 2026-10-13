package com.ersaz.hermesmobile.agent.tools

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import org.json.JSONArray
import org.json.JSONObject

class ToolContext(val runtime: HermesRuntime, val agent: Agent, val listener: AgentListener) {
    val session get() = agent.session
}

abstract class Tool(
    val name: String,
    val toolset: String,
    val description: String,
    val emoji: String = "⚡",
) {
    abstract val parameters: JSONObject
    abstract suspend fun run(args: JSONObject, ctx: ToolContext): String

    fun schema(): JSONObject = JSONObject().put("type", "function").put(
        "function", JSONObject().put("name", name).put("description", description).put("parameters", parameters)
    )

    /** One-line preview of the call for progress displays (Hermes' "┊ 💻 terminal ls -la"). */
    open fun preview(args: JSONObject): String {
        for (key in listOf("command", "query", "path", "url", "urls", "name", "action", "goal", "text", "message", "question", "pattern")) {
            if (args.has(key)) return args.opt(key).toString().replace('\n', ' ').take(90)
        }
        return ""
    }
}

/** Builders for compact JSON-schema definitions. */
fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject().put("type", "object")
        .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
        .put("required", JSONArray(required))

fun str(desc: String, enum: List<String>? = null): JSONObject =
    JSONObject().put("type", "string").put("description", desc).also { if (enum != null) it.put("enum", JSONArray(enum)) }

fun int(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)
fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)
fun arr(desc: String, items: JSONObject): JSONObject = JSONObject().put("type", "array").put("description", desc).put("items", items)

fun ok(vararg pairs: Pair<String, Any?>): String =
    JSONObject().put("success", true).also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }.toString()

fun fail(msg: String): String = JSONObject().put("success", false).put("error", msg).toString()

class ToolRegistry(private val runtime: HermesRuntime) {

    data class Toolset(val name: String, val description: String)

    val toolsets = listOf(
        Toolset("terminal", "Sandbox shell commands and background processes"),
        Toolset("file", "Read, write, patch and search files"),
        Toolset("web", "Web search and page extraction"),
        Toolset("memory", "Persistent memory (MEMORY.md / USER.md)"),
        Toolset("skills", "Procedural memory: list, view, create and patch skills"),
        Toolset("todo", "Task planning list"),
        Toolset("session_search", "Recall past conversations"),
        Toolset("cronjob", "Scheduled tasks"),
        Toolset("delegation", "Spawn isolated subagents"),
        Toolset("messaging", "Send messages to connected platforms"),
        Toolset("clarify", "Ask the user clarifying questions"),
        Toolset("tts", "Text to speech"),
        Toolset("vision", "Analyze images"),
        Toolset("device", "Android phone features (clipboard, notifications, URLs, battery)"),
        Toolset("bots", "Bot Mode: hand work to other bots in your roster (@mentions)"),
        Toolset("mcp", "Tools from connected MCP servers"),
        Toolset("browser", "Real web browser: navigate, click, type, screenshots"),
    )

    val all: List<Tool> by lazy {
        listOf(
            TerminalTool(), ProcessTool(),
            ReadFileTool(), WriteFileTool(), PatchTool(), SearchFilesTool(),
            WebSearchTool(), WebExtractTool(),
            MemoryTool(),
            SkillsListTool(), SkillViewTool(), SkillManageTool(),
            TodoTool(),
            SessionSearchTool(),
            CronjobTool(),
            DelegateTool(),
            SendMessageTool(),
            ClarifyTool(),
            TextToSpeechTool(),
            VisionTool(),
            AndroidDeviceTool(),
            MessageBotTool(),
            com.ersaz.hermesmobile.browser.BrowserTool(),
            com.ersaz.hermesmobile.device.PhoneTool(),
            com.ersaz.hermesmobile.device.WeatherTool(),
        )
    }

    fun byName(name: String) = all.firstOrNull { it.name == name } ?: runtime.mcp.tools().firstOrNull { it.name == name }

    fun available(enabledToolsets: Set<String>?, depth: Int, botDisabledToolsets: Set<String> = emptySet()): List<Tool> {
        val s = runtime.settings
        val disabledSets = s.disabledToolsetSet()
        val disabledTools = s.disabledToolSet()
        return (all + runtime.mcp.tools()).filter { t ->
            val setOk = (enabledToolsets?.contains(t.toolset) ?: (t.toolset !in disabledSets)) && t.toolset !in botDisabledToolsets
            val subagentBlocked = depth > 0 && t.name in setOf("delegate_task", "clarify", "memory", "send_message", "cronjob", "message_bot")
            setOk && t.name !in disabledTools && !subagentBlocked &&
                !(t.toolset == "memory" && !s.memoryEnabled && !s.userProfileEnabled)
        }
    }

    fun schemas(tools: List<Tool>): JSONArray = JSONArray().also { a -> tools.forEach { a.put(it.schema()) } }
}
