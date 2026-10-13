package com.ersaz.hermesmobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.cron.CronJob
import com.ersaz.hermesmobile.cron.Schedule
import com.ersaz.hermesmobile.service.HermesService
import com.ersaz.hermesmobile.ui.theme.LocalSkin

// ------------------------------------------------------------------ shared bits

@Composable
fun SectionTitle(text: String) {
    val skin = LocalSkin.current
    Text(text, color = skin.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, color = LocalSkin.current.dim, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
fun Field(label: String, value: String, secret: Boolean = false, number: Boolean = false, singleLine: Boolean = true, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; onChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = skin.text, fontSize = 15.sp)
            hint?.let { Text(it, color = skin.dim, fontSize = 12.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(skin.surface, RoundedCornerShape(10.dp)).padding(12.dp)) { content() }
}

// ------------------------------------------------------------------ sessions

@Composable
fun SessionsScreen(rt: HermesRuntime, vm: ChatViewModel, open: () -> Unit) {
    val skin = LocalSkin.current
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh, query) {
        if (query.isBlank()) rt.sessions.list().map { it.id to Triple(it.title, it.source, "${it.messageCount} msgs · ${Commands.timestamp(it.updatedAt)}") }
        else rt.sessions.search(query, 30).map { it.sessionId to Triple(it.title, "search", it.snippets.firstOrNull().orEmpty()) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search all sessions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { vm.newSession(); open() }) { Text("＋ New session") }
        }
        LazyColumn {
            items(list, key = { it.first }) { (id, info) ->
                Row(Modifier.fillMaxWidth().clickable { vm.openSession(id); open() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.first, color = if (id == vm.agent.session.id) skin.accent else skin.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${sourceIcon(info.second)} ${info.second} · ${info.third}", color = skin.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.deleteSession(id); refresh++ }) { Icon(Icons.Default.Delete, "delete", tint = skin.dim) }
                }
                HorizontalDivider(color = skin.selection)
            }
        }
    }
}

fun sourceIcon(source: String) = when (source) {
    "telegram" -> "✈️"; "discord" -> "🎮"; "slack" -> "💬"; "cron" -> "⏰"; "terminal" -> "💻"; "subagent" -> "🔀"; else -> "📱"
}

// ------------------------------------------------------------------ skills

@Composable
fun SkillsScreen(rt: HermesRuntime, vm: ChatViewModel, open: () -> Unit) {
    val skin = LocalSkin.current
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<com.ersaz.hermesmobile.agent.SkillStore.Skill?>(null) }
    var creating by remember { mutableStateOf(false) }
    val skills = remember(refresh) { rt.skills.list() }

    editing?.let { s ->
        var text by remember(s) { mutableStateOf(s.file.readText()) }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("✏️ ${s.name}", color = skin.title, fontSize = 18.sp)
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.file.writeText(text); editing = null; refresh++ }) { Text("Save") }
                OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
                TextButton(onClick = { rt.skills.delete(s); editing = null; refresh++ }) { Text("Delete", color = skin.error) }
            }
        }
        return
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf("general") }
        var body by remember { mutableStateOf("---\nname: my-skill\ndescription: What this skill does and when to use it\n---\n\n# My skill\n\n## When to use\n\n## Steps\n1. \n") }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cat, { cat = it }, label = { Text("category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (name.isNotBlank()) { rt.skills.create(name, body, cat); creating = false; refresh++ } }) { Text("Create") }
                OutlinedButton(onClick = { creating = false }) { Text("Cancel") }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Hint("Skills are Hermes' procedural memory (SKILL.md in ~/.hermes/skills). The agent creates and improves them as it works; tap ▶ to invoke one as /<name>.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }) { Text("＋ New skill") }
                OutlinedButton(onClick = { vm.input = "/learn "; open() }) { Text("/learn …") }
            }
        }
        skills.groupBy { it.category }.forEach { (cat, list) ->
            item { SectionTitle("📁 $cat") }
            items(list, key = { it.dir.path }) { s ->
                Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text(s.description, color = skin.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.input = "/${s.name} "; open() }) { Icon(Icons.Default.PlayArrow, "use", tint = skin.ok) }
                }
            }
        }
        if (skills.isEmpty()) item { Text("No skills yet.", color = skin.dim, modifier = Modifier.padding(16.dp)) }
    }
}

// ------------------------------------------------------------------ memory

@Composable
fun MemoryScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    var tab by remember { mutableIntStateOf(0) }
    val files = listOf(
        "MEMORY.md" to rt.memory.file("memory"),
        "USER.md" to rt.memory.file("user"),
        "SOUL.md" to java.io.File(rt.hermesHome, "SOUL.md"),
    )
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = skin.surface) {
            files.forEachIndexed { i, (n, _) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(n) }) }
        }
        val (name, file) = files[tab]
        var text by remember(tab) { mutableStateOf(if (file.exists()) file.readText() else "") }
        var saved by remember(tab) { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Hint(when (name) {
                "MEMORY.md" -> "Agent notes injected into every session (${rt.memory.usage("memory")} chars). Entries are separated by a line containing §."
                "USER.md" -> "What Hermes knows about you (${rt.memory.usage("user")} chars). Entries separated by §."
                else -> "SOUL.md — Hermes' identity / persona. Replaces the default system identity."
            })
            OutlinedTextField(text, { text = it; saved = false }, modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { file.parentFile?.mkdirs(); file.writeText(text); saved = true }) { Text("Save") }
                if (saved) Text("  ✓ saved — applies to new sessions", color = skin.ok, fontSize = 12.sp)
            }
            Toggle("Agent notes (MEMORY.md)", rt.settings.memoryEnabled) { rt.settings.memoryEnabled = it }
            Toggle("User profile (USER.md)", rt.settings.userProfileEnabled) { rt.settings.userProfileEnabled = it }
        }
    }
}

// ------------------------------------------------------------------ cron

@Composable
fun CronScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    var refresh by remember { mutableIntStateOf(0) }
    val jobs = remember(refresh) { rt.cron.all() }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("every 1d") }
    var prompt by remember { mutableStateOf("") }
    var deliver by remember { mutableStateOf("local") }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Hint("Hermes runs these unattended in fresh sessions. Schedules: 30m · every 2h · 0 9 * * * · 2026-10-20T09:00. " +
                "Deliver to local (notification), telegram / discord / slack (home channel) or platform:chat_id. Runs while the app or the messaging gateway is alive.")
            SectionTitle("New scheduled task")
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(schedule, { schedule = it }, label = { Text("Schedule") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt (self-contained)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
            OutlinedTextField(deliver, { deliver = it }, label = { Text("Deliver to") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = skin.error, fontSize = 12.sp) }
            Button(onClick = {
                error = Schedule.validate(schedule) ?: if (prompt.isBlank()) "Prompt is required" else null
                if (error == null) {
                    rt.cron.add(CronJob(name = name.ifBlank { prompt.take(40) }, prompt = prompt, schedule = schedule, deliver = deliver, origin = "local"))
                    rt.ensureServiceForCron()
                    name = ""; prompt = ""; refresh++
                }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("⏰ Schedule") }
            SectionTitle("Jobs (${jobs.size})")
        }
        items(jobs, key = { it.id }) { j ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, color = if (j.enabled) skin.accent else skin.dim, fontWeight = FontWeight.Bold)
                        Text(j.describe(), color = skin.dim, fontSize = 12.sp)
                        if (j.lastStatus.isNotBlank()) Text("last: ${j.lastStatus} ${if (j.lastRunAt > 0) Commands.timestamp(j.lastRunAt) else ""}", color = skin.label, fontSize = 12.sp)
                    }
                    Switch(j.enabled, { en ->
                        rt.cron.update(j.id) { it.enabled = en; if (en) it.nextRunAt = Schedule.next(it.schedule, System.currentTimeMillis(), true) ?: 0 }
                        refresh++
                    })
                }
                Text(j.prompt, color = skin.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick = { rt.cronRunner.runNowAsync(j); refresh++ }) { Text("Run now") }
                    TextButton(onClick = { rt.cron.remove(j.id); refresh++ }) { Text("Delete", color = skin.error) }
                }
                if (j.lastOutput.isNotBlank()) Text(j.lastOutput.take(600), color = skin.text.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ gateway / bot mode

@Composable
fun GatewayScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val s = rt.settings
    val ctx = LocalContext.current
    val running by rt.gateway.running.collectAsState()
    val pending by rt.gateway.pending.collectAsState()
    val log by rt.gateway.log.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val paired = remember(refresh, pending) { rt.gateway.pairedUsers() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Hint("The messaging gateway runs Hermes on this phone as a bot for other apps: one agent and one memory across Telegram, Discord and Slack, with per-chat sessions, slash commands, approvals and cron delivery. A foreground service keeps it alive.")
        Card {
            Toggle("☤ Messaging gateway", s.botMode, if (running) "Gateway running" else "Gateway stopped") { on ->
                s.botMode = on
                if (on) { HermesService.start(ctx); rt.gateway.start() } else { rt.gateway.stop(); HermesService.stop(ctx) }
                refresh++
            }
            Toggle("Keep CPU awake (wake lock)", s.keepAwake, "Recommended for reliable bots") { s.keepAwake = it }
            Row {
                TextButton(onClick = { rt.gateway.start(); refresh++ }) { Text("Restart gateway") }
                TextButton(onClick = {
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                        runCatching {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                }) { Text("Disable battery optimization") }
            }
        }
        Text(rt.gateway.statusText(), color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

        if (pending.isNotEmpty()) {
            SectionTitle("🔑 Pairing requests")
            pending.forEach { p ->
                Card {
                    Text("${p.userName} on ${p.platform} (id ${p.userId}) — code ${p.code}", color = skin.text)
                    Row {
                        Button(onClick = { rt.gateway.approvePairing(p); refresh++ }) { Text("Approve") }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = { rt.gateway.rejectPairing(p) }) { Text("Reject") }
                    }
                }
            }
        }

        SectionTitle("✈️ Telegram")
        Hint("Create a bot with @BotFather and paste its token. Allowed users: numeric IDs or usernames (comma separated); '*' allows everyone. Others get a pairing code.")
        Field("Bot token", s.telegramToken, secret = true) { s.telegramToken = it.trim() }
        Field("Allowed users", s.telegramAllowed) { s.telegramAllowed = it }
        Toggle("Require @mention in groups", s.telegramRequireMention) { s.telegramRequireMention = it }

        SectionTitle("🎮 Discord")
        Hint("Developer Portal → Bot → token. Enable the MESSAGE CONTENT intent. Allowed users: Discord user IDs.")
        Field("Bot token", s.discordToken, secret = true) { s.discordToken = it.trim() }
        Field("Allowed users", s.discordAllowed) { s.discordAllowed = it }
        Toggle("Require @mention in servers", s.discordRequireMention) { s.discordRequireMention = it }

        SectionTitle("💬 Slack (Socket Mode)")
        Hint("Enable Socket Mode; bot token (xoxb-…) with chat:write, app_mentions:read, im:history; app token (xapp-…) with connections:write.")
        Field("Bot token (xoxb-)", s.slackBotToken, secret = true) { s.slackBotToken = it.trim() }
        Field("App token (xapp-)", s.slackAppToken, secret = true) { s.slackAppToken = it.trim() }
        Field("Allowed users", s.slackAllowed) { s.slackAllowed = it }

        SectionTitle("Tool progress in chats")
        Row { listOf("off", "new", "all", "verbose").forEach { m ->
            TextButton(onClick = { s.gatewayToolProgress = m; refresh++ }) {
                Text(m, color = if (s.gatewayToolProgress == m) skin.accent else skin.dim)
            }
        } }

        if (paired.isNotEmpty()) {
            SectionTitle("Paired users")
            paired.forEach { (p, id, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$p · $n ($id)", color = skin.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { rt.gateway.revoke(p, id); refresh++ }) { Text("Revoke", color = skin.error) }
                }
            }
        }

        SectionTitle("Log")
        Text(log.takeLast(60).joinToString("\n").ifBlank { "(empty)" }, color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(40.dp))
    }
}
