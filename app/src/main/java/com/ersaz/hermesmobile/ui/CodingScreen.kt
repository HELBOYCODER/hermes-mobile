package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.coding.CodingDefaults
import com.ersaz.hermesmobile.mcp.McpStatus
import com.ersaz.hermesmobile.ui.theme.LocalSkin

@Composable
fun CodingScreen(rt: HermesRuntime, vm: ChatViewModel, onChat: () -> Unit, onNavigate: (String) -> Unit) {
    val skin = LocalSkin.current
    val statuses by rt.mcp.status.collectAsState()
    var project by remember { mutableStateOf(rt.settings.codingProject) }
    var error by remember { mutableStateOf("") }
    val status = statuses[CodingDefaults.SERVER_ID]
    fun prepare(task: String) {
        val path = project.trim().trimEnd('/').ifBlank { "/root/projects" }
        val root = rt.sandbox.hostPath("/root/projects").canonicalFile
        val selected = rt.sandbox.hostPath(path).canonicalFile
        if (!path.startsWith("/root/projects") || (selected != root && !selected.path.startsWith(root.path + "/")) || !selected.isDirectory) {
            error = "Choose an existing project inside /root/projects. Clone or create it in Terminal first."
            return
        }
        rt.settings.codingProject = path
        vm.input = "Work in $path. $task Use the matching bundled skill, inspect the repository and its instructions before edits, and keep credentials in Environment."
        onChat()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Build with context", color = skin.title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Hint("A focused workspace for exploring, changing and reviewing code. Your chosen model runs the task; these workflows prepare a draft for you to review before sending.")
        SectionTitle("Project")
        OutlinedTextField(project, { project = it; error = "" }, label = { Text("Project path") }, placeholder = { Text("/root/projects/my-app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = skin.error, fontSize = 12.sp)
        TextButton(onClick = { onNavigate("Terminal") }) { Text("Open Terminal to clone or create a project") }
        SectionTitle("Coding workflows")
        SettingsHubRow("Explore repository", "Map architecture, dependencies and test commands", Icons.Default.AccountTree) { prepare("Explore the repository with the explore-repository skill. Explain its architecture, entry points, build/test commands and current Git state without changing files.") }
        SettingsHubRow("Implement a feature", "Inspect, plan, edit and verify a focused change", Icons.Default.Code) { prepare("Use the implement-feature skill. First ask what feature I want, then implement and verify it within the existing architecture.") }
        SettingsHubRow("Review changes", "Find concrete defects in the current diff", Icons.Default.RateReview) { prepare("Review current changes with the review-code skill. Prioritize reproducible bugs and missing behavior tests; report file references and severity. Do not edit files.") }
        SettingsHubRow("Fix a failing test", "Reproduce the failure before changing code", Icons.Default.BugReport) { prepare("Use systematic-debugging and verify-changes. Identify and reproduce a failing check, fix its cause, and rerun the relevant checks. Ask for the failure details if none are available.") }
        SectionTitle("Local coding tools")
        Text(when (status) {
            is McpStatus.Connected -> "Connected · ${status.tools} read-only tools"
            is McpStatus.Connecting -> "Preparing local tools…"
            is McpStatus.Failed -> "Unavailable: ${status.message}"
            McpStatus.Disabled -> "Disabled in MCP settings"
            else -> "Tools are being prepared; check MCP servers for status."
        }, color = if (status is McpStatus.Connected) skin.ok else skin.label, fontSize = 13.sp)
        Hint("Bundled MCP: project overview, bounded source search and Git changes. No external account or package download. File edits and command execution use the existing Hermes tools and approval settings.")
        SectionTitle("Workspace setup")
        SettingsHubRow("MCP servers", "Inspect local tools or connect a documentation provider", Icons.Default.Extension) { onNavigate("Mcp") }
        SettingsHubRow("Skills library", "Explore, implement, review, debug and verify", Icons.Default.AutoStories) { onNavigate("Skills") }
        SettingsHubRow("Settings", "GitHub identity, Environment and tool approvals", Icons.Default.Settings) { onNavigate("Settings") }
        Spacer(Modifier.height(32.dp))
    }
}
