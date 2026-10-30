package com.ersaz.hermesmobile.ui

import androidx.compose.runtime.*
import com.ersaz.hermesmobile.HermesRuntime

/** Phone equivalent of each desktop agent tool, in one line. Desktop GUI
 *  automation has no phone equivalent and is deliberately absent: the
 *  "phone" toolset below drives the Android UI (via Shizuku) instead, and
 *  never a desktop GUI. Image/video generation is not a tool in this app
 *  at all and is not claimed here. */
private fun phoneNote(name: String): String = when (name) {
    "terminal" -> "Desktop shell → Alpine/PRoot sandbox shell (Node and Python installable via apk)"
    "file" -> "Desktop file tools → sandbox filesystem (/root/projects; /sdcard only if bound)"
    "web" -> "Desktop web search/fetch → DuckDuckGo/Tavily/Brave search + page extraction"
    "browser" -> "Desktop browser automation → in-app Chrome-engine browser, with user handover for login/CAPTCHA"
    "memory" -> "Desktop memory → on-device MEMORY.md / USER.md"
    "skills" -> "Desktop skills → bundled + user skills in SkillStore"
    "todo" -> "Desktop task list → same todo tool"
    "session_search" -> "Desktop session recall → on-device session search"
    "cronjob" -> "Desktop scheduled tasks → CronRunner (jobs run while the app/service is alive)"
    "delegation" -> "Desktop sub-agents → isolated sub-sessions (depth-limited; some tools blocked in sub-agents)"
    "messaging" -> "Desktop messaging gateway → Telegram/Discord adapters + send_message"
    "clarify" -> "Desktop clarify → ask the user in chat"
    "tts" -> "Desktop text-to-speech → on-device TTS"
    "vision" -> "Desktop vision → image analysis, including browser screenshots"
    "device" -> "Phone-only: clipboard, notifications, URLs, battery, weather"
    "phone" -> "Phone equivalent of desktop GUI automation: Shizuku tap/swipe/type/screenshot/app install — Android UI only"
    "github" -> "Desktop GitHub tooling → GitHub REST API with the user's own token from Settings"
    "bots" -> "Bot Mode: hand work to other bots in the user's roster"
    "mcp" -> "Desktop MCP tools → tools from connected MCP servers"
    else -> ""
}

/** "Tools on this phone" — the agent toolsets that were previously only
 *  reachable under Settings → Tools, now visible and toggleable from the
 *  Coding screen. Toggling writes the same setting the agent's
 *  ToolRegistry reads, so the change takes effect for the next turn.
 *  Dangerous commands still go through the existing approval flow. */
@Composable
fun ToolsPanel(rt: HermesRuntime, onNavigate: (String) -> Unit = {}) {
    var refresh by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = refresh
    SectionTitle("Tools on this phone")
    Hint("These are the phone equivalents of the desktop Hermes agent's tools. Switch a toolset off and the agent will not use it; the same toggles live in Settings → Tools. Code runs in the Alpine sandbox, files live in the sandbox filesystem, and the browser is the in-app browser — nothing here automates a desktop GUI, and there is no image/video generation tool in this app.")
    val disabled = rt.settings.disabledToolsetSet()
    SettingsHubRow("Open MCP Hub", "Add, test, enable and inspect MCP servers (built-in and custom)", androidx.compose.material.icons.Icons.Filled.Extension) { onNavigate("Mcp") }
    rt.tools.toolsets.forEach { t ->
        val note = phoneNote(t.name)
        val hint = if (note.isBlank()) t.description else "${t.description}. $note"
        Toggle(t.name, t.name !in disabled, hint) { on ->
            rt.settings.setToolsetEnabled(t.name, on)
            refresh++
        }
    }
}
