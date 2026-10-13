package com.ersaz.hermesmobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ersaz.hermesmobile.bots.Bot
import com.ersaz.hermesmobile.bots.RosterEntry
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.cron.CronJob
import com.ersaz.hermesmobile.cron.Schedule
import com.ersaz.hermesmobile.service.HermesService
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bot Mode (port of Nous' Hermes Bot Mode): a roster of named agents — each its own profile with chat, avatar,
 * personality (SOUL), memory, model and routines — plus bot-to-bot messaging, @mentions and group rooms.
 */
@Composable
fun BotsScreen(rt: HermesRuntime, vm: ChatViewModel, openChat: () -> Unit) {
    // "roster" | "edit:<id>" | "new" | "routines:<id>" | "sessions:<id>" | "group:<name>"
    var route by rememberSaveable { mutableStateOf("roster") }
    val back = { route = "roster" }
    val open = { bot: Bot -> vm.openBot(bot); openChat() }
    when {
        route == "new" -> BotEditor(rt, null, onDone = { route = "roster" })
        route.startsWith("edit:") -> BotEditor(rt, rt.bots.get(route.removePrefix("edit:")), onDone = { route = "roster" })
        route.startsWith("routines:") -> rt.bots.get(route.removePrefix("routines:"))?.let { BotRoutines(rt, it, back) } ?: back()
        route.startsWith("sessions:") -> rt.bots.get(route.removePrefix("sessions:"))?.let { BotSessions(rt, vm, it, back, openChat) } ?: back()
        route.startsWith("group:") -> GroupRoomScreen(rt, route.removePrefix("group:"), back)
        else -> Roster(rt, open, go = { route = it })
    }
}

// ------------------------------------------------------------------ roster

@Composable
private fun Roster(rt: HermesRuntime, open: (Bot) -> Unit, go: (String) -> Unit) {
    val skin = LocalSkin.current
    val running by rt.botHub.runningIds.collectAsState()
    var entries by remember { mutableStateOf<List<RosterEntry>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var moveBot by remember { mutableStateOf<Bot?>(null) }
    var deleteBot by remember { mutableStateOf<Bot?>(null) }

    LaunchedEffect(tick, running) {
        while (true) {
            entries = withContext(Dispatchers.IO) { rt.botHub.roster() }
            delay(4000)
        }
    }
    val groups = remember(entries) {
        entries.filter { it.bot.group.isNotBlank() }.groupBy { it.bot.group.trim().lowercase() }.values
            .sortedBy { it.first().bot.group.trim().lowercase() }.associate { it.first().bot.group.trim() to it }
    }
    val ungrouped = entries.filter { it.bot.group.isBlank() }
    val active = entries.filter { it.running || it.activeRecently }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bots", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("One chat per bot. A bot is a Hermes profile: its own personality, memory, model and routines.", color = skin.dim, fontSize = 12.sp)
                }
                Button(onClick = { go("new") }) { Icon(Icons.Default.Add, null); Text(" New agent") }
            }
        }
        if (active.isNotEmpty()) item {
            Text("Active now", color = skin.label, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(active, key = { it.bot.id }) { e ->
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(skin.surface).clickable { open(e.bot) }.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(e.bot, 22.dp, working = e.running)
                        if (e.running) ThinkingOrbs(OrbMode.Tool, 22.dp)
                        Text(" ${e.bot.name}", color = skin.text, fontSize = 13.sp)
                    }
                }
            }
        }
        if (entries.size == 1) item {
            Hint("Tap “New agent” to add a bot — a researcher, a coder, a songwriting partner… Bots can message each other: write “@researcher have a look at this” in any chat and the active bot hands it off.")
        }
        item { Spacer(Modifier.height(8.dp)) }
        items(ungrouped, key = { it.bot.id }) { e -> BotRow(rt, e, open, go, onMove = { moveBot = it }, onDelete = { deleteBot = it }, onChanged = { tick++ }) }
        groups.forEach { (name, members) ->
            item(key = "group:$name") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(0.15f), color = skin.border.copy(alpha = 0.5f))
                    Text("  $name (${members.size})  ", color = skin.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(Modifier.weight(1f), color = skin.border.copy(alpha = 0.5f))
                    if (members.size in 2..Bot.MAX_GROUP_MEMBERS) TextButton(onClick = { go("group:$name") }) { Text("Open chat") }
                }
            }
            items(members, key = { "g:" + it.bot.id }) { e -> BotRow(rt, e, open, go, onMove = { moveBot = it }, onDelete = { deleteBot = it }, onChanged = { tick++ }) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    moveBot?.let { bot ->
        var newGroup by remember { mutableStateOf("") }
        val existing = rt.bots.groups().keys
        AlertDialog(
            onDismissRequest = { moveBot = null },
            title = { Text("Move ${bot.name} to group") },
            text = {
                Column {
                    existing.forEach { g -> TextButton(onClick = { rt.bots.save(bot.apply { group = g }); moveBot = null; tick++ }) { Text(g) } }
                    OutlinedTextField(newGroup, { newGroup = it }, singleLine = true, label = { Text("New group name") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { rt.bots.save(bot.apply { group = newGroup.trim() }); moveBot = null; tick++ }) { Text(if (newGroup.isBlank()) "No group" else "Create") } },
            dismissButton = { TextButton(onClick = { moveBot = null }) { Text("Cancel") } },
        )
    }
    deleteBot?.let { bot ->
        AlertDialog(
            onDismissRequest = { deleteBot = null },
            title = { Text("Delete ${bot.name}?") },
            text = { Text("This permanently removes the bot, its memory, chats and routines. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { deleteBotCompletely(rt, bot); deleteBot = null; tick++ }) { Text("Delete", color = skin.error) }
            },
            dismissButton = { TextButton(onClick = { deleteBot = null }) { Text("Cancel") } },
        )
    }
}

private fun deleteBotCompletely(rt: HermesRuntime, bot: Bot) {
    rt.botHub.sessionsOf(bot).forEach { rt.sessions.delete(it.id) }
    rt.cron.all().filter { it.botId == bot.id }.forEach { rt.cron.remove(it.id) }
    if (bot.group.isNotBlank()) rt.botHub.forgetRoom(bot.group)
    rt.bots.delete(bot.id)
}

@Composable
private fun BotRow(
    rt: HermesRuntime, e: RosterEntry, open: (Bot) -> Unit, go: (String) -> Unit,
    onMove: (Bot) -> Unit, onDelete: (Bot) -> Unit, onChanged: () -> Unit,
) {
    val skin = LocalSkin.current
    var menu by remember { mutableStateOf(false) }
    val bot = e.bot
    Row(Modifier.fillMaxWidth().clickable { open(bot) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            BotAvatar(bot, 46.dp, working = e.running)
            if (e.activeRecently) Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(skin.ok))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.name, color = skin.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bot.title.isNotBlank()) Text("  ${bot.title}", color = skin.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val a2a = e.preview.startsWith("Message from 🤖")
            val preview = if (a2a) e.preview.substringAfter("): ", e.preview) else e.preview
            val sender = if (a2a) e.preview.substringAfter("(@").substringBefore(")") else ""
            Text(
                (if (a2a) "🤖 @$sender: " else if (e.previewRole == "user") "You: " else "") + preview.ifBlank { bot.description.ifBlank { "No messages yet" } },
                color = skin.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (e.updatedAt > 0) Text(Commands.timestamp(e.updatedAt).substringAfter(' ', Commands.timestamp(e.updatedAt)), color = skin.dim, fontSize = 11.sp)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "more", tint = skin.label) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit profile") }, onClick = { menu = false; go("edit:${bot.id}") })
                    DropdownMenuItem(text = { Text("Sessions") }, onClick = { menu = false; go("sessions:${bot.id}") })
                    DropdownMenuItem(text = { Text("Routines") }, onClick = { menu = false; go("routines:${bot.id}") })
                    if (!bot.isDefault) {
                        DropdownMenuItem(text = { Text("Move to group") }, onClick = { menu = false; onMove(bot) })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; rt.bots.duplicate(bot.id); onChanged() })
                        DropdownMenuItem(text = { Text("Delete profile", color = skin.error) }, onClick = { menu = false; onDelete(bot) })
                    }
                }
            }
        }
    }
    HorizontalDivider(color = skin.border.copy(alpha = 0.25f))
}

// ------------------------------------------------------------------ editor

@Composable
private fun BotEditor(rt: HermesRuntime, existing: Bot?, onDone: () -> Unit) {
    val skin = LocalSkin.current
    val isNew = existing == null
    val home = rt.hermesHome
    val soulFile = java.io.File(home, "SOUL.md")
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var shape by remember { mutableIntStateOf(existing?.shape ?: ((rt.bots.all().size * 3) % Bot.SHAPES)) }
    var color by remember { mutableIntStateOf(existing?.color ?: ((rt.bots.all().size * 7 + 2) % Bot.COLORS)) }
    var soul by remember { mutableStateOf(if (existing?.isDefault == true) soulFile.takeIf { it.exists() }?.readText().orEmpty() else existing?.soul ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var disabled by remember { mutableStateOf(existing?.disabledToolsets?.toSet() ?: emptySet()) }
    var pickModel by remember { mutableStateOf(false) }
    var cloneFrom by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(!isNew) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = skin.accent) }
            Text(if (isNew) "New agent" else "Edit ${existing?.name}", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) { BotAvatar(shape, color, 84.dp, animate = true) }
        Field("Name", name) { name = it }
        Field("Title (e.g. Research analyst)", title) { title = it }
        Field("Description — what this bot is for", description, singleLine = false) { description = it }

        Text("Avatar shape", color = skin.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            items((0 until Bot.SHAPES).toList()) { i ->
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == shape) skin.selection else skin.surface).clickable { shape = i }.padding(6.dp)) {
                    BotAvatar(i, color, 38.dp)
                }
            }
        }
        Text("Color", color = skin.dim, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            items((0 until Bot.COLORS).toList()) { i ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(BotColors[i]).clickable { color = i }
                    .then(if (i == color) Modifier.padding(3.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), CircleShape) else Modifier))
            }
        }

        if (isNew) {
            val options = listOf("" to "Blank profile") + rt.bots.all().map { it.id to "Clone ${it.name}" }
            Picker("Start from", options, cloneFrom) { id ->
                cloneFrom = id
                rt.bots.get(id)?.let { src ->
                    soul = if (src.isDefault) soulFile.takeIf { it.exists() }?.readText().orEmpty() else src.soul
                    model = src.model; disabled = src.disabledToolsets.toSet(); advanced = true
                }
            }
        }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced ▴" else "Advanced ▾") }
        if (advanced) {
            Field("SOUL — personality & standing instructions", soul, singleLine = false) { soul = it }
            if (existing?.isDefault != true) {
                Field("Model (blank = app-wide model)", model) { model = it.trim() }
                OutlinedButton(onClick = { pickModel = true }) { Text("Browse models") }
            }
            SectionTitle("Toolsets")
            rt.tools.toolsets.forEach { t ->
                Toggle(t.name, t.name !in disabled, t.description) { on -> disabled = if (on) disabled - t.name else disabled + t.name }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = name.isNotBlank(), onClick = {
                if (existing == null) {
                    val b = rt.bots.create(name, title, description, soul.trim(), model, shape, color)
                    rt.bots.save(b.apply { disabledToolsets = disabled.sorted() })
                    // A clone inherits memory like Hermes' profile duplicate.
                    rt.bots.get(cloneFrom)?.let { src -> for (t in listOf("memory", "user")) rt.bots.memoryFor(src).file(t).takeIf { it.exists() }?.copyTo(rt.bots.memoryFor(b).file(t), overwrite = true) }
                } else {
                    existing.name = name.trim(); existing.title = title.trim(); existing.description = description.trim()
                    existing.shape = shape; existing.color = color
                    if (existing.isDefault) { soulFile.writeText(soul.trim() + "\n") } else { existing.soul = soul.trim(); existing.model = model }
                    existing.disabledToolsets = disabled.sorted()
                    rt.bots.save(existing)
                }
                onDone()
            }) { Text(if (isNew) "Create" else "Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
        Spacer(Modifier.height(40.dp))
    }
    if (pickModel) ModelPickerDialog(rt, model, onPick = { model = it.id; pickModel = false }, onDismiss = { pickModel = false })
}

// ------------------------------------------------------------------ routines

@Composable
private fun BotRoutines(rt: HermesRuntime, bot: Bot, back: () -> Unit) {
    val skin = LocalSkin.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val jobs = remember(refresh) { rt.cron.all().filter { if (bot.isDefault) it.botId == null else it.botId == bot.id } }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("every 1d") }
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = skin.accent) }
            BotAvatar(bot, 30.dp)
            Text("  ${bot.name} · Routines", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Hint("Recurring tasks this bot runs by itself — “summarize my inbox every morning”. Backed by the same scheduler as Scheduled tasks; results arrive as a notification from ${bot.name}.")
        jobs.forEach { j ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, color = skin.text, fontWeight = FontWeight.SemiBold)
                        Text(j.describe(), color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Switch(checked = j.enabled, onCheckedChange = { on ->
                        rt.cron.update(j.id) { c -> c.enabled = on; if (on) c.nextRunAt = Schedule.next(c.schedule, System.currentTimeMillis(), true) ?: 0 }
                        if (on) rt.ensureServiceForCron(); refresh++
                    })
                    IconButton(onClick = { rt.cronRunner.runNowAsync(j); refresh++ }) { Icon(Icons.Default.PlayArrow, "run now", tint = skin.accent) }
                    IconButton(onClick = { rt.cron.remove(j.id); refresh++ }) { Icon(Icons.Default.Delete, "delete", tint = skin.error) }
                }
                if (j.lastOutput.isNotBlank()) Text(j.lastOutput.take(500), color = skin.text.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
        if (jobs.isEmpty()) Text("No routines yet.", color = skin.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))

        SectionTitle("New routine")
        Field("Name", name) { name = it }
        Field("Schedule (30m · every 2h · 0 9 * * * · 2026-10-20T09:00)", schedule) { schedule = it }
        Field("What should ${bot.name} do?", prompt, singleLine = false) { prompt = it }
        if (error.isNotBlank()) Text(error, color = skin.error, fontSize = 12.sp)
        Button(onClick = {
            val bad = Schedule.validate(schedule.trim())
            when {
                bad != null -> error = bad
                prompt.isBlank() -> error = "Describe the task."
                else -> {
                    rt.cron.add(CronJob(name = name.ifBlank { prompt.take(40) }, prompt = prompt.trim(), schedule = schedule.trim(),
                        deliver = "local", origin = "local", botId = bot.id.takeUnless { bot.isDefault }))
                    HermesService.start(ctx)
                    name = ""; prompt = ""; error = ""; refresh++
                }
            }
        }) { Text("Add routine") }
        Spacer(Modifier.height(40.dp))
    }
}

// ------------------------------------------------------------------ per-bot sessions

@Composable
private fun BotSessions(rt: HermesRuntime, vm: ChatViewModel, bot: Bot, back: () -> Unit, openChat: () -> Unit) {
    val skin = LocalSkin.current
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh, query) {
        rt.botHub.sessionsOf(bot).filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) }.take(200)
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = skin.accent) }
            BotAvatar(bot, 30.dp)
            Text("  ${bot.name} · Sessions", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        LazyColumn {
            items(list, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { vm.openSession(s.id); openChat() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceIcon(s.source) + " ", fontSize = 16.sp)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = skin.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.messageCount} msgs · ${Commands.timestamp(s.updatedAt)}", color = skin.dim, fontSize = 11.sp)
                    }
                    IconButton(onClick = { vm.deleteSession(s.id); refresh++ }) { Icon(Icons.Default.Delete, "delete", tint = skin.dim) }
                }
                HorizontalDivider(color = skin.border.copy(alpha = 0.2f))
            }
        }
    }
}

// ------------------------------------------------------------------ group room

@Composable
private fun GroupRoomScreen(rt: HermesRuntime, group: String, back: () -> Unit) {
    val skin = LocalSkin.current
    val room = remember(group) { rt.botHub.room(group) }
    val messages by room.messages.collectAsState()
    val busy by room.busy.collectAsState()
    val needsUser by room.needsUser.collectAsState()
    val speaking by room.speaking.collectAsState()
    val members = remember(messages.size) { room.members() }
    val byId = remember(members) { members.associateBy { it.id } }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = skin.accent) }
            Column(Modifier.weight(1f)) {
                Text(group, color = skin.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row { members.forEach { BotAvatar(it, 20.dp, working = it.id == speaking); Spacer(Modifier.size(4.dp)) } }
            }
            if (needsUser) Text("needs you", color = skin.warn, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = { room.clear() }) { Text("Clear", color = skin.dim) }
        }
        HorizontalDivider(color = skin.border.copy(alpha = 0.4f))
        if (members.size < 2) Hint("A group chat needs 2–6 bots. Move bots into “$group” from the roster.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            if (messages.isEmpty()) item { Hint("Say something. @mention a bot to address it directly; otherwise everyone may answer (or pass). Bots can pull each other in, and use @user when they need your call.") }
            items(messages) { m ->
                val bot = byId[m.sender]
                val mine = m.sender == "user"
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
                    if (!mine) { if (bot != null) BotAvatar(bot, 30.dp) else Text("⚙️", fontSize = 22.sp); Spacer(Modifier.size(8.dp)) }
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if (mine) skin.selection else skin.surface).padding(10.dp).weight(1f, fill = false)) {
                        if (!mine) Text(bot?.name ?: "System", color = skin.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        MarkdownText(m.text)
                    }
                }
            }
            if (busy) item { ThinkingIndicator(OrbMode.Writing, "${speaking?.let { byId[it]?.name } ?: "…"} is typing") }
        }
        HorizontalDivider(color = skin.border.copy(alpha = 0.4f))
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message the group · @name to address a bot", color = skin.dim) },
                shape = RoundedCornerShape(14.dp))
            if (busy) IconButton(onClick = { room.stop() }) { Icon(Icons.Default.Close, "stop", tint = skin.error) }
            else IconButton(enabled = members.size >= 2, onClick = { room.send(input); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "send", tint = skin.accent) }
        }
    }
}
