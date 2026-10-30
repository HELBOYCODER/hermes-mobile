package com.ersaz.hermesmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.files.DownloadsExporter
import com.ersaz.hermesmobile.mcp.McpServer
import com.ersaz.hermesmobile.mcp.McpStatus
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import java.io.File
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MCP Hub — the designed MCP section (replaces the old single-form MCP
 * screen). Groups: Built-in (bundled defaults + in-app toolset entries)
 * and Custom (user servers). Every row: name, transport badge, status
 * dot, tool count, enabled switch. Detail screens carry the real tool
 * list (never a hardcoded list), per-tool toggles, logs, restart,
 * duplicate and delete. A custom server is never shown as connected
 * until a test actually succeeds; a failed test saves it as error.
 *
 * Secrets note (honest limitation): this app has no
 * EncryptedSharedPreferences dependency, so MCP secrets (env values,
 * header tokens) live in the same store as every other provider key in
 * this app — servers.json / Settings SharedPreferences, unencrypted at
 * rest. They are never sent to any server except their own (each
 * server's headers/env are built per server), and exports redact them.
 */
private val KNOWN_BUILTIN_IDS = setOf("hermes-code", "context7-docs", "github-readonly", "framelink-figma")

private sealed interface HubScreen {
    data object List : HubScreen
    data object Add : HubScreen
    data class Detail(val id: String) : HubScreen
    data class Builtin(val id: String) : HubScreen // "browser" | "filesystem"
}

private fun statusLabel(st: McpStatus?, enabled: Boolean): Pair<String, Color> = when (st) {
    is McpStatus.Connected -> "OK · ${st.tools} tools" to Color(0xFF2E9E5B)
    is McpStatus.Failed -> "Error" to Color(0xFFC0392B)
    is McpStatus.NeedsAuth -> "Needs sign-in" to Color(0xFFB7791F)
    is McpStatus.Connecting -> "Connecting…" to Color(0xFFB7791F)
    is McpStatus.Disabled -> "Off" to Color(0xFF888888)
    null -> if (enabled) "Not started" to Color(0xFF888888) else "Off" to Color(0xFF888888)
}

@Composable
fun McpHubScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    var screen by remember { mutableStateOf<HubScreen>(HubScreen.List) }
    BackHandler(enabled = screen != HubScreen.List) { screen = HubScreen.List }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        when (val sc = screen) {
            is HubScreen.List -> McpHubList(rt, onAdd = { screen = HubScreen.Add }, onDetail = { screen = HubScreen.Detail(it) }, onBuiltin = { screen = HubScreen.Builtin(it) })
            is HubScreen.Add -> McpHubAddScreen(rt, onBack = { screen = HubScreen.List }, onSaved = { screen = HubScreen.List })
            is HubScreen.Detail -> McpHubDetailScreen(rt, sc.id, onBack = { screen = HubScreen.List })
            is HubScreen.Builtin -> McpBuiltinDetailScreen(rt, sc.id, onBack = { screen = HubScreen.List })
        }
        if (screen != HubScreen.List) {
            Row { OutlinedButton(onClick = { screen = HubScreen.List }) { Text("← Back to Hub") } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun McpHubList(rt: HermesRuntime, onAdd: () -> Unit, onDetail: (String) -> Unit, onBuiltin: (String) -> Unit) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val statuses by rt.mcp.status.collectAsState()
    val tick by rt.mcp.tick.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tickValue = tick
    val servers = remember(tick) { rt.mcp.servers() }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }
    var exportMsg by remember { mutableStateOf("") }

    SectionTitle("MCP Hub")
    Hint("Servers give Hermes new tools. Built-in servers ship with the app; custom servers are yours. A custom server is never shown as connected until a test actually succeeds — a failed test saves it as an error with its error text, never as working.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAdd) { Text("＋ Add custom MCP") }
    }

    Text("Built-in", color = skin.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    // In-app toolset entries (transport "builtin"): their tool lists come
    // from the real agent tool schemas, never a hardcoded list.
    McpBuiltinRow(rt, "browser", "Browser tools", "builtin") { onBuiltin("browser") }
    McpBuiltinRow(rt, "filesystem", "Filesystem / Sandbox", "builtin") { onBuiltin("filesystem") }
    servers.filter { it.builtin || it.id in KNOWN_BUILTIN_IDS }.forEach { s ->
        McpServerRow(rt, s, statuses[s.id], onDetail)
    }
    Text("Custom", color = skin.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    val custom = servers.filter { !it.builtin && it.id !in KNOWN_BUILTIN_IDS }
    if (custom.isEmpty()) Text("No custom servers yet. Add one above, or import a Claude-style config below.", color = skin.dim, fontSize = 12.5.sp)
    custom.forEach { s -> McpServerRow(rt, s, statuses[s.id], onDetail) }

    SectionTitle("Import / export")
    Hint("Import a Claude-style {\"mcpServers\": {…}} config. Invalid or duplicate entries are skipped and reported by name; imported servers start disabled and untested — test each one before use. Export covers custom servers only, with secret values redacted (re-importing an export will not restore keys).")
    OutlinedTextField(importText, { importText = it }, label = { Text("Paste mcpServers JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val (imported, skipped) = importHubJson(rt, importText)
            importMsg = "Imported ${imported.size}" + (if (imported.isNotEmpty()) ": ${imported.joinToString()}" else "") +
                (if (skipped.isNotEmpty()) ". Skipped ${skipped.size}: ${skipped.joinToString()}" else ".")
            if (imported.isNotEmpty()) importText = ""
        }) { Text("Import") }
        OutlinedButton(onClick = {
            scope.launch {
                exportMsg = runCatching {
                    val customServers = rt.mcp.servers().filter { !it.builtin && it.id !in KNOWN_BUILTIN_IDS }
                    val root = JSONObject().put("mcpServers", JSONObject().also { m ->
                        customServers.forEach { s ->
                            val o = s.toJson()
                            o.optJSONObject("env")?.let { env -> env.keys().forEach { k -> env.put(k, "••••••") } }
                            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> h.put(k, "••••••") } }
                            m.put(s.id, o)
                        }
                    })
                    val f = File(ctx.cacheDir, "mcp-hub-export.json").also { it.writeText(root.toString(2)) }
                    DownloadsExporter.save(ctx, f)
                    "Exported ${customServers.size} custom server(s), secrets redacted, to Downloads/Hermes/mcp-hub-export.json."
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }) { Text("Export custom (redacted)") }
    }
    if (importMsg.isNotBlank()) Text(importMsg, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    if (exportMsg.isNotBlank()) Text(exportMsg, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(24.dp))
}

/** Parses a Claude-style config itself so skipped entries can be named. */
private fun importHubJson(rt: HermesRuntime, raw: String): Pair<List<String>, List<String>> {
    val imported = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return emptyList<String>() to listOf("invalid JSON")
    val container = root.optJSONObject("mcpServers") ?: root.optJSONObject("servers") ?: root
    for (key in container.keys()) {
        val obj = container.optJSONObject(key) ?: run { skipped += key; continue }
        val server = McpServer.fromJson(key, obj) ?: run { skipped += key; continue }
        if (rt.mcp.store.get(server.id) != null) { skipped += "$key (duplicate)"; continue }
        rt.mcp.store.upsert(server.copy(enabled = false, autoStart = false, builtin = false))
        imported += key
    }
    return imported to skipped
}

@Composable
private fun McpBuiltinRow(rt: HermesRuntime, toolset: String, title: String, badge: String, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val tools = remember { rt.tools.all.filter { it.toolset == toolset } }
    val enabled = toolset !in rt.settings.disabledToolsetSet()
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = refresh
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) Color(0xFF2E9E5B) else Color(0xFF888888)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = skin.text, fontSize = 14.sp)
            Text("$badge · ${tools.size} tools" + if (toolset == "browser") " · in-app browser host" else " · /root/projects", color = skin.dim, fontSize = 11.5.sp)
        }
        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { rt.settings.setToolsetEnabled(toolset, it); refresh++ })
    }
}

@Composable
private fun McpServerRow(rt: HermesRuntime, s: McpServer, st: McpStatus?, onDetail: (String) -> Unit) {
    val skin = LocalSkin.current
    val (label, dot) = statusLabel(st, s.enabled)
    val needsKey = s.id == "framelink-figma" && rt.mcp.framelinkNeedsKey()
    val toolCount = if (st is McpStatus.Connected) st.tools else rt.mcp.defsFor(s.id).size
    Row(Modifier.fillMaxWidth().clickable { onDetail(s.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (needsKey) Color(0xFFB7791F) else dot))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(s.name, color = skin.text, fontSize = 14.sp)
            Text("${s.transportBadge} · $toolCount tools" + when {
                needsKey -> " · Needs Figma key"
                st is McpStatus.Failed -> " · ${st.message.take(80)}"
                else -> " · $label"
            }, color = skin.dim, fontSize = 11.5.sp, maxLines = 2)
        }
        androidx.compose.material3.Switch(checked = s.enabled, onCheckedChange = { rt.mcp.setEnabled(s.id, it) })
    }
}

private fun parseKvLines(text: String): Map<String, String> = text.lines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()

private fun formatKvLines(map: Map<String, String>): String =
    map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

@Composable
private fun McpHubAddScreen(rt: HermesRuntime, onBack: () -> Unit, onSaved: () -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var isRemote by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("/root") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    SectionTitle("Add custom MCP")
    Hint("Auto-start stays OFF until a test succeeds — that is the rule, not a bug. A failed test still saves the entry, but only as an error with its error text, never as working.")
    Field("Name", name) { name = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { isRemote = false }) { Text(if (!isRemote) "● stdio (local)" else "stdio (local)") }
        OutlinedButton(onClick = { isRemote = true }) { Text(if (isRemote) "● Remote (SSE/HTTP)" else "Remote (SSE/HTTP)") }
    }
    if (!isRemote) {
        Field("Command (runs inside the Alpine sandbox, never on the Android host)", command) { command = it.trim() }
        Field("Arguments (space-separated, quotes honoured)", argsText) { argsText = it }
        Field("Environment (KEY=value per line; \${VAR} resolves from Settings → Environment)", envText, singleLine = false) { envText = it }
        Field("Working directory (guest path, default /root)", cwd) { cwd = it.trim() }
    } else {
        Field("Server URL (https://…)", url) { url = it.trim() }
        Field("Headers (KEY=value per line; \${VAR} resolves from Settings → Environment)", headersText, singleLine = false) { headersText = it }
    }
    if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("Connected")) skin.ok else skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            // Inline validation: nothing is saved until these pass.
            val error = when {
                name.isBlank() -> "Enter a name."
                rt.mcp.servers().any { it.name.equals(name.trim(), true) || it.id.equals(name.trim(), true) } -> "A server with that name already exists."
                !isRemote && command.isBlank() -> "Enter a command."
                isRemote && !(url.startsWith("http://") || url.startsWith("https://")) -> "Enter a valid http(s) URL."
                !isRemote && cwd.isNotBlank() && !cwd.startsWith("/") -> "Working directory must be an absolute guest path (e.g. /root/projects)."
                else -> null
            }
            if (error != null) { msg = error; return@Button }
            val id = rt.mcp.store.uniqueId(name.trim())
            val entry = McpServer(
                id = id, displayName = name.trim(),
                command = if (!isRemote) command else null,
                args = if (!isRemote) com.ersaz.hermesmobile.mcp.McpInput.tokenize(argsText) else emptyList(),
                env = if (!isRemote) parseKvLines(envText) else emptyMap(),
                url = if (isRemote) url else null,
                headers = if (isRemote) parseKvLines(headersText) else emptyMap(),
                transport = if (isRemote) "auto" else "auto",
                cwd = cwd.ifBlank { "/root" },
                enabled = true, autoStart = false, builtin = false,
            )
            busy = true; msg = "Testing connection…"
            rt.mcp.update(entry)
            scope.launch {
                val st = rt.mcp.connect(entry.id, interactive = true)
                // Re-read fresh: setStatus may have persisted a lastError
                // that a stale copy would overwrite.
                val fresh = rt.mcp.store.get(entry.id) ?: entry
                if (st is McpStatus.Connected) {
                    rt.mcp.update(fresh.copy(enabled = true, autoStart = true))
                    msg = "Connected: ${st.tools} tools. Auto-start is now ON."
                } else {
                    rt.mcp.update(fresh.copy(enabled = false, autoStart = false))
                    msg = "Not connected — saved as error: " + when (st) {
                        is McpStatus.Failed -> st.message
                        is McpStatus.NeedsAuth -> st.message
                        is McpStatus.Connecting -> "still connecting"
                        else -> "test did not complete"
                    } + " Fix the entry and test again; it is never shown as working."
                }
                busy = false
            }
        }) { Text(if (busy) "Testing…" else "Test & save") }
        OutlinedButton(enabled = !busy, onClick = {
            if (name.isBlank()) { msg = "Enter a name."; return@OutlinedButton }
            val id = rt.mcp.store.uniqueId(name.trim())
            rt.mcp.update(McpServer(id = id, displayName = name.trim(),
                command = if (!isRemote) command.ifBlank { null } else null,
                args = if (!isRemote) com.ersaz.hermesmobile.mcp.McpInput.tokenize(argsText) else emptyList(),
                env = if (!isRemote) parseKvLines(envText) else emptyMap(),
                url = if (isRemote) url.ifBlank { null } else null,
                headers = if (isRemote) parseKvLines(headersText) else emptyMap(),
                cwd = cwd.ifBlank { "/root" }, enabled = false, autoStart = false, builtin = false))
            onSaved()
        }) { Text("Save draft") }
        TextButton(onClick = onBack) { Text("Cancel") }
    }
}

@Composable
private fun McpHubDetailScreen(rt: HermesRuntime, id: String, onBack: () -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val statuses by rt.mcp.status.collectAsState()
    val tick by rt.mcp.tick.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tickValue = tick
    val entry = remember(tick) { rt.mcp.store.get(id) } ?: run {
        Text("Server not found.", color = skin.error); return
    }
    var displayName by remember(entry) { mutableStateOf(entry.name) }
    var note by remember(entry) { mutableStateOf(entry.note) }
    var command by remember(entry) { mutableStateOf(entry.command.orEmpty()) }
    var argsText by remember(entry) { mutableStateOf(entry.args.joinToString(" ")) }
    var envText by remember(entry) { mutableStateOf(formatKvLines(entry.env)) }
    var url by remember(entry) { mutableStateOf(entry.url.orEmpty()) }
    var headersText by remember(entry) { mutableStateOf(formatKvLines(entry.headers)) }
    var cwd by remember(entry) { mutableStateOf(entry.cwd) }
    var timeout by remember(entry) { mutableStateOf(entry.timeoutSec.toString()) }
    var allowlist by remember(entry) { mutableStateOf(entry.allowedTools.sorted().joinToString(", ")) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var logTick by remember { mutableIntStateOf(0) }
    val st = statuses[id]
    val (label, dot) = statusLabel(st, entry.enabled)
    val needsKey = id == "framelink-figma" && rt.mcp.framelinkNeedsKey()
    val defs = remember(tick) { rt.mcp.defsFor(id) }

    SectionTitle(entry.name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (needsKey) Color(0xFFB7791F) else dot))
        Text("  ${if (needsKey) "Needs Figma key" else label}" + (if (st is McpStatus.Failed) ": ${st.message.take(300)}" else ""), color = skin.label, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
    }
    if (needsKey) Hint("Framelink has no usable Figma key: set FIGMA_API_KEY in Settings → Environment, or type the key literally in the env field below (a literal value passes through; \${FIGMA_API_KEY} resolves from Environment). Then test again. Never paste keys into chat.")
    if (entry.lastError.isNotBlank() && st !is McpStatus.Failed) Text("Last error: ${entry.lastError.take(300)}", color = skin.dim, fontSize = 12.sp)
    Text("Transport: ${entry.transportBadge}" + (if (entry.isStdio) " · runs in the Alpine sandbox" else " · remote"), color = skin.dim, fontSize = 12.sp)

    Field("Display name", displayName) { displayName = it }
    Field("Note", note) { note = it }
    if (entry.isStdio) {
        Field("Command", command) { command = it.trim() }
        Field("Arguments", argsText) { argsText = it }
        Field("Environment (KEY=value per line)", envText, singleLine = false) { envText = it }
        Field("Working directory (guest path)", cwd) { cwd = it.trim() }
    } else {
        Field("URL", url) { url = it.trim() }
        Field("Headers (KEY=value per line)", headersText, singleLine = false) { headersText = it }
    }
    Field("Timeout (seconds, 0 = transport default)", timeout, number = true) { timeout = it.filter { c -> c.isDigit() } }
    Field("Allowlist (comma-separated tool names, empty = all allowed)", allowlist) { allowlist = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            val updated = entry.copy(
                displayName = displayName.trim().ifBlank { entry.id },
                note = note, command = command.ifBlank { null },
                args = com.ersaz.hermesmobile.mcp.McpInput.tokenize(argsText),
                env = parseKvLines(envText), url = url.ifBlank { null },
                headers = parseKvLines(headersText), cwd = cwd.ifBlank { "/root" },
                timeoutSec = timeout.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                allowedTools = allowlist.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
            rt.mcp.update(updated); msg = "Saved."
        }) { Text("Save") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; msg = "Restarting…"
            scope.launch {
                val st2 = rt.mcp.connect(id, interactive = true)
                msg = when (st2) {
                    is McpStatus.Connected -> "Restarted: ${st2.tools} tools (schemas refreshed)."
                    is McpStatus.Failed -> "Restart failed: ${st2.message}"
                    is McpStatus.NeedsAuth -> "Needs sign-in: ${st2.message}"
                    else -> "Restart finished: $st2"
                }
                busy = false
            }
        }) { Text(if (busy) "Working…" else "Restart / test") }
        OutlinedButton(onClick = {
            val copy = entry.copy(
                id = rt.mcp.store.uniqueId("${entry.id}-copy"),
                displayName = "${entry.name} (copy)", enabled = false, autoStart = false, builtin = false, lastError = "",
            )
            rt.mcp.update(copy); msg = "Duplicated as ${copy.name} (disabled, untested)."
        }) { Text("Duplicate") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = skin.error) }
    }
    if (msg.isNotBlank()) Text(msg, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${entry.name}?") },
        text = { Text("The server entry and its cached tools are removed. This cannot be undone (except by re-adding it).") },
        confirmButton = { TextButton(onClick = { rt.mcp.remove(id); confirmDelete = false; onBack() }) { Text("Delete", color = skin.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )

    SectionTitle("Tools (${defs.size}) — real schema from this server")
    Hint("Toggling a tool here writes the server's denylist, which the agent's tool gating reads immediately (including the generic mcp tool — a denied tool cannot be reached around it). Restart refreshes the schemas.")
    if (defs.isEmpty()) Text("No tools cached yet — test/restart the server to fetch its real tool list.", color = skin.dim, fontSize = 12.5.sp)
    defs.forEach { d ->
        val off = d.name in entry.deniedTools || (entry.allowedTools.isNotEmpty() && d.name !in entry.allowedTools)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(d.description.take(160).ifBlank { "(no description)" }, color = skin.dim, fontSize = 11.5.sp, maxLines = 2)
            }
            androidx.compose.material3.Switch(checked = !off, onCheckedChange = { on ->
                val denied = entry.deniedTools.toMutableSet()
                if (on) denied.remove(d.name) else denied.add(d.name)
                rt.mcp.update(rt.mcp.store.get(id)?.copy(deniedTools = denied) ?: entry.copy(deniedTools = denied))
            })
        }
    }

    SectionTitle("Logs")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { logTick++ }) { Text("Refresh logs") }
    }
    val logs = remember(tick, logTick) { rt.mcp.diagnostics(id) }
    Text(
        if (logs.isBlank()) (if (entry.isStdio) "(no stdio output yet)" else "Remote servers have no process logs here.") else logs.takeLast(4000),
        color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun McpBuiltinDetailScreen(rt: HermesRuntime, toolset: String, onBack: () -> Unit) {
    val skin = LocalSkin.current
    val tools = remember { rt.tools.all.filter { it.toolset == toolset } }
    val enabled = toolset !in rt.settings.disabledToolsetSet()
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = refresh
    SectionTitle(if (toolset == "browser") "Browser tools" else "Filesystem / Sandbox")
    Hint(if (toolset == "browser") "Built-in toolset on Hermes' own browser host (the Playwright-style tools: navigate, snapshot, click, type/fill, wait, screenshot). This is not a wire MCP server — toggling it enables/disables the agent's browser toolset."
    else "Built-in toolset over the sandbox filesystem (/root/projects). Toggling it enables/disables the agent's file toolset. Paths stay inside the project root.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Enabled", color = skin.text, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { rt.settings.setToolsetEnabled(toolset, it); refresh++ })
    }
    SectionTitle("Tools (${tools.size}) — real agent schemas")
    tools.forEach { t ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(t.name, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(t.description.take(200), color = skin.dim, fontSize = 11.5.sp, maxLines = 2)
        }
    }
    Spacer(Modifier.height(16.dp))
}
