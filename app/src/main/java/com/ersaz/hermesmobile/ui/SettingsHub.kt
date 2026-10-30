package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.theme.LocalSkin

enum class SettingsCategory(val title: String, val subtitle: String, val group: String, val icon: ImageVector, val keywords: String) {
    Models("Models & accounts", "Provider, Nous login, model and context — add your own z.ai, OpenRouter or custom provider", "Model & providers", Icons.Default.SmartToy, "api token key reasoning output zai openrouter provider"),
    Engine("Agent engine", "Hermes native or Pi harness, and Spec-first chat", "Model & providers", Icons.Default.Psychology, "engine pi harness native spec"),
    Personality("Personality & theme", "Identity, skin and response display", "Appearance", Icons.Default.Palette, "prompt appearance color status"),
    Input("Chat & voice", "Text size, voice language and message queue", "Chat & thinking", Icons.Default.Mic, "font language input recording"),
    GitHub("GitHub", "Account connection and commit identity", "Development", Icons.Default.Code, "git repository token"),
    Router("Local 9Router", "Server, dashboard password and models", "9Router", Icons.Default.Hub, "port endpoint password free"),
    Remote("Remote WebUI", "Connect to a hosted Hermes interface", "Development", Icons.Default.Public, "url webui remote"),
    Environment("Environment", "Encrypted keys and variables for your tools", "Data & privacy", Icons.Default.Key, "secret api key variable env"),
    Access("Permissions & phone", "Android permissions and Shizuku control", "Data & privacy", Icons.Default.Security, "storage microphone notification battery install shizuku"),
    Backup("Backup & restore", "Export or restore your app data", "Data & privacy", Icons.Default.Backup, "zip export import"),
    Tools("Agent tools", "Toolsets, approvals, search and timeouts", "Development", Icons.Default.Build, "web brave tavily command permission"),
    Sandbox("Linux sandbox", "Storage, packages and runtime maintenance", "Development", Icons.Default.Terminal, "rootfs alpine mirror proot linux"),
    About("About & updates", "Version information and release checks", "About", Icons.Default.Info, "version update release")
}

@Composable
fun SettingsHub(rt: com.ersaz.hermesmobile.HermesRuntime, onSelect: (SettingsCategory) -> Unit, onNavigate: (String) -> Unit) {
    val skin = LocalSkin.current
    var query by remember { mutableStateOf("") }
    val matching = SettingsCategory.entries.filter { "$it ${it.title} ${it.subtitle} ${it.keywords}".contains(query.trim(), true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Make Hermes yours", color = skin.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        // Account / profile row at the top (structure only, Hermes' own card):
        // Nous login state, current provider + model, and engine.
        Surface(color = skin.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            SettingsHubRow(
                if (rt.nousAuth.signedIn) "Nous: ${rt.settings.nousAccount.ifBlank { "signed in" }}" else "Not signed in to Nous",
                "${rt.settings.provider} · ${rt.settings.model.ifBlank { "no model" }} · engine: ${rt.settings.engine}",
                Icons.Default.AccountCircle,
            ) { onSelect(SettingsCategory.Models) }
        }
        Text("Accounts, tools and your device — one place for each.", color = skin.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search settings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (query.isBlank()) {
            SettingsHubRow("Startup recovery", "Open safely or resume normal startup", Icons.Default.HealthAndSafety) {
                onNavigate("Recovery")
            }
            SectionTitle("Development workspace")
            SettingsHubRow("Coding workspace", "Projects, coding workflows and local MCP", Icons.Default.Code) { onNavigate("Coding") }
            SettingsHubRow("MCP Hub", "Built-in and custom MCP servers, tools, logs and health", Icons.Default.Extension) { onNavigate("Mcp") }
            SettingsHubRow("Skills library", "Reusable development and debugging workflows", Icons.Default.AutoStories) { onNavigate("Skills") }
            listOf("Terminal", "Files", "Browser", "SubAgents").forEach { route ->
                SettingsHubRow(route, "Development tools", Icons.Default.Build) { onNavigate(route) }
            }
            SectionTitle("Automation & diagnostics")
            listOf("Memory", "Cron", "Gateway", "WebUi", "Usage", "Logs").forEach { route ->
                SettingsHubRow(route, "Open ${route.lowercase()}", Icons.Default.Tune) { onNavigate(route) }
            }
        }
        matching.groupBy { it.group }.forEach { (group, categories) ->
            SectionTitle(group)
            Surface(color = skin.surface, shape = RoundedCornerShape(18.dp)) {
                Column {
                    categories.forEachIndexed { index, category ->
                        SettingsHubRow(category.title, category.subtitle, category.icon) { onSelect(category) }
                        if (index < categories.lastIndex) HorizontalDivider(color = skin.border, modifier = Modifier.padding(start = 62.dp))
                    }
                }
            }
        }
        if (matching.isEmpty()) Text("No matching settings", color = skin.dim, modifier = Modifier.padding(16.dp))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun SettingsHubRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(skin.selection, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = skin.accent, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = skin.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = skin.dim, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = skin.dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsDetailHeader(category: SettingsCategory, onBack: () -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to settings", tint = skin.label) }
        Icon(category.icon, null, tint = skin.accent, modifier = Modifier.size(22.dp))
        Text(category.title, color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, modifier = Modifier.padding(start = 10.dp))
    }
}
