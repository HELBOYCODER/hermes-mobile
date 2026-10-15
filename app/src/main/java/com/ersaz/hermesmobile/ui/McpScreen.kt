package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.mcp.McpServer
import com.ersaz.hermesmobile.mcp.McpStatus
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/** Custom MCP servers: paste a URL / command / JSON and the app connects, checks and signs in by itself. */
@Composable
fun McpScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val statuses by rt.mcp.status.collectAsState()
    val tick by rt.mcp.tick.collectAsState()
    val servers = remember(tick) { rt.mcp.servers() }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var confirmDelete by remember { mutableStateOf<McpServer?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Coding integrations")
        Hint("Hermes Code is bundled and works locally in /root/projects. Context7 and GitHub are preconfigured but disabled until you add the required key and enable them. Header placeholders such as \${GITHUB_TOKEN} read Environment; never paste keys into chat.")
        Hint("MCP servers give Hermes new tools — GitHub, Notion, databases, search… Paste a server address and Hermes connects, checks it, and opens the sign-in page itself if the server needs a login.")
        Card {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("URL, command or JSON") },
                placeholder = { Text("https://mcp.example.com/mcp   ·   npx -y @modelcontextprotocol/server-everything   ·   {\"mcpServers\": …}") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = input.isNotBlank(), onClick = {
                    val r = rt.mcp.addFromInput(input)
                    message = r.error ?: "Added ${r.ids.joinToString()} — connecting…"
                    if (r.error == null) input = ""
                }) { Text("Add & connect") }
                if (message.isNotBlank()) Text(message, color = if (message.startsWith("Added")) skin.ok else skin.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }

        if (servers.isEmpty()) Text("No MCP servers yet.", color = skin.dim, modifier = Modifier.padding(vertical = 12.dp))
        servers.forEach { s ->
            val st = statuses[s.id] ?: if (s.enabled) McpStatus.Connecting("Waiting…") else McpStatus.Disabled
            ServerCard(rt, s, st, onEdit = { editing = s }, onDelete = { confirmDelete = s })
        }
        Spacer(Modifier.height(40.dp))
    }

    editing?.let { s -> EditServerDialog(s, onDismiss = { editing = null }, onSave = { rt.mcp.update(it); rt.mcp.reconnectAsync(it.id, interactive = false); editing = null }) }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${s.id}?") },
            text = { Text("Its tools disappear and any saved sign-in is deleted.") },
            confirmButton = { TextButton(onClick = { rt.mcp.remove(s.id); confirmDelete = null }) { Text("Remove", color = skin.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerCard(rt: HermesRuntime, s: McpServer, st: McpStatus, onEdit: () -> Unit, onDelete: () -> Unit) {
    val skin = LocalSkin.current
    var showTools by remember { mutableStateOf(false) }
    val (dot, line) = when (st) {
        is McpStatus.Connected -> skin.ok to "Connected · ${st.tools} tools · ${st.serverName}"
        is McpStatus.Connecting -> skin.warn to st.step
        is McpStatus.NeedsAuth -> skin.warn to "🔑 ${st.message}"
        is McpStatus.Failed -> skin.error to st.message
        McpStatus.Disabled -> skin.dim to "Disabled"
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.id, color = skin.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text((if (s.isStdio) "⌨ " else "🌐 ") + s.summary, color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = s.enabled, onCheckedChange = { rt.mcp.setEnabled(s.id, it) })
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st is McpStatus.Connecting) ThinkingOrbs(OrbMode.Tool, 26.dp)
            Text(line, color = dot, fontSize = 12.sp)
        }
        if (s.note.isNotBlank()) Text(s.note, color = skin.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            if (st is McpStatus.NeedsAuth) Button(onClick = { rt.mcp.signInAsync(s.id) }) { Text("Sign in") }
            else if (s.url != null && rt.mcp.oauth.store.hasToken(s.id)) TextButton(onClick = { rt.mcp.signOut(s.id) }) { Text("Sign out") }
            TextButton(onClick = { rt.mcp.reconnectAsync(s.id) }) { Text("Reconnect") }
            if (st is McpStatus.Connected) TextButton(onClick = { showTools = !showTools }) { Text(if (showTools) "Hide tools" else "Tools") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Remove", color = skin.error) }
        }
        if (showTools) rt.mcp.allDefs().filter { it.server == s.id }.forEach { d ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(d.name, color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (d.description.isNotBlank()) Text(d.description.take(160), color = skin.dim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EditServerDialog(s: McpServer, onDismiss: () -> Unit, onSave: (McpServer) -> Unit) {
    var url by remember { mutableStateOf(s.url.orEmpty()) }
    var command by remember { mutableStateOf(s.command.orEmpty()) }
    var args by remember { mutableStateOf(s.args.joinToString(" ")) }
    var headers by remember { mutableStateOf(s.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var env by remember { mutableStateOf(s.env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var note by remember { mutableStateOf(s.note) }
    var clientId by remember { mutableStateOf(s.oauthClientId.orEmpty()) }
    var transport by remember { mutableStateOf(s.transport) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${s.id}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (s.isStdio) {
                    Field("Command", command) { command = it }
                    Field("Arguments (space separated)", args) { args = it }
                    Field("Environment (KEY=VALUE per line)", env, singleLine = false) { env = it }
                } else {
                    Field("Server URL", url) { url = it.trim() }
                    Picker("Transport", listOf("auto" to "Auto-detect", "http" to "Streamable HTTP", "sse" to "Legacy SSE"), transport) { transport = it }
                    Field("Headers (Name: value per line — e.g. an API key)", headers, singleLine = false) { headers = it }
                    Field("OAuth client id (only if the server has no auto-registration)", clientId) { clientId = it.trim() }
                }
                Field("Note for the model (what is this server for?)", note) { note = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(s.copy(
                    url = url.ifBlank { null }.takeIf { !s.isStdio }, command = command.ifBlank { null }.takeIf { s.isStdio },
                    args = if (s.isStdio) com.ersaz.hermesmobile.mcp.McpInput.tokenize(args) else s.args,
                    headers = headers.lines().mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() } }.toMap(),
                    env = env.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1] } }.toMap(),
                    note = note.trim(), oauthClientId = clientId.ifBlank { null }, transport = transport,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
