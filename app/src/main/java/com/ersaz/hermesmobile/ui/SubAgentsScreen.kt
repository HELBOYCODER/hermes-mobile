package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.jobs.AgentJob
import com.ersaz.hermesmobile.agent.jobs.JobState
import com.ersaz.hermesmobile.agent.jobs.SubAgentDef
import com.ersaz.hermesmobile.agent.jobs.SubAgentLimits
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.delay

/** Sub-agents: the roster the main model delegates to automatically, plus a live view of running jobs. */
@Composable
fun SubAgentsScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val s = rt.settings
    val tick by rt.agentJobs.tick.collectAsState()
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock++ } }
    var refresh by remember { mutableIntStateOf(0) }
    val roster = remember(refresh) { rt.agentJobs.store.all() }
    val jobs = remember(tick, clock) { rt.agentJobs.all().take(20) }
    var editing by remember { mutableStateOf<SubAgentDef?>(null) }
    var pickLight by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Hint("When a job is big, Hermes hands parts of it to sub-agents on its own — each works in a private context, in parallel (3 at a time, extras queue), and reports back as a new message. You never have to ask.")
        Card {
            Toggle("Allow delegation", s.subAgentsEnabled, "Turn off to keep Hermes working alone") { s.subAgentsEnabled = it; refresh++ }
            Field("Light model (used when Hermes picks sub_model for simple work; blank = same as chat)", s.subModel) { s.subModel = it.trim() }
            OutlinedButton(onClick = { pickLight = true }) { Text("Browse models") }
        }

        SectionTitle("Running & recent")
        if (jobs.isEmpty()) Text("Nothing delegated yet.", color = skin.dim, fontSize = 13.sp)
        jobs.forEach { JobCard(rt, it) }

        SectionTitle("Roster (${roster.size}/${SubAgentLimits.MAX_COUNT})")
        roster.forEach { d ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.builtIn) "  · built-in" else "", color = skin.text, fontWeight = FontWeight.SemiBold)
                        Text(d.description, color = skin.dim, fontSize = 12.sp)
                        Text("Model: " + d.model.ifBlank { "Auto (same as chat)" }, color = skin.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { editing = d }) { Text("Edit") }
                }
            }
        }
        if (roster.size < SubAgentLimits.MAX_COUNT) Button(onClick = { editing = SubAgentDef(name = "", description = "") }, modifier = Modifier.padding(top = 8.dp)) { Text("Add sub-agent") }
        Spacer(Modifier.height(40.dp))
    }

    editing?.let { d ->
        EditDialog(rt, d, isNew = roster.none { it.id == d.id }, onDismiss = { editing = null }, onSave = { rt.agentJobs.store.upsert(it); refresh++; editing = null },
            onDelete = { rt.agentJobs.store.remove(d.id); refresh++; editing = null })
    }
    if (pickLight) ModelPickerDialog(rt, s.subModel, onPick = { s.subModel = it.id; pickLight = false; refresh++ }, onDismiss = { pickLight = false })
}

@Composable
private fun JobCard(rt: HermesRuntime, j: AgentJob) {
    val skin = LocalSkin.current
    var open by remember { mutableStateOf(false) }
    val color = when (j.state) {
        JobState.RUNNING -> skin.accent; JobState.DONE -> skin.ok; JobState.QUEUED -> skin.dim
        JobState.INTERRUPTED -> skin.warn; else -> skin.error
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (j.state == JobState.RUNNING) ThinkingOrbs(OrbMode.Tool, 30.dp)
            Column(Modifier.weight(1f).padding(start = if (j.state == JobState.RUNNING) 6.dp else 0.dp)) {
                Text("${j.agentName} · ${j.state.name.lowercase()} · ${j.elapsedSec}s", color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(j.task.take(120), color = skin.dim, fontSize = 12.sp)
                if (j.currentTool.isNotBlank() && !j.state.finished) Text("🔧 ${j.currentTool}", color = skin.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (!j.state.finished && j.state != JobState.INTERRUPTED) TextButton(onClick = { rt.agentJobs.cancel(j) }) { Text("Cancel", color = skin.error) }
            if (j.result.isNotBlank()) TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Result") }
        }
        if (open) Text(j.result.take(4000), color = skin.text, fontSize = 12.sp)
    }
}

@Composable
private fun EditDialog(rt: HermesRuntime, d: SubAgentDef, isNew: Boolean, onDismiss: () -> Unit, onSave: (SubAgentDef) -> Unit, onDelete: () -> Unit) {
    val skin = LocalSkin.current
    var name by remember { mutableStateOf(d.name) }
    var desc by remember { mutableStateOf(d.description) }
    var instr by remember { mutableStateOf(d.instructions) }
    var model by remember { mutableStateOf(d.model) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New sub-agent" else "Edit ${d.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name (what Hermes calls it)", name) { name = it.take(SubAgentLimits.NAME_MAX) }
                Field("When should Hermes use it? (${desc.length}/${SubAgentLimits.DESCRIPTION_MAX})", desc, singleLine = false) { desc = it.take(SubAgentLimits.DESCRIPTION_MAX) }
                Field("Instructions (${instr.length}/${SubAgentLimits.INSTRUCTIONS_MAX})", instr, singleLine = false) { instr = it.take(SubAgentLimits.INSTRUCTIONS_MAX) }
                Field("Pinned model (blank = Auto)", model) { model = it.trim() }
                OutlinedButton(onClick = { pick = true }) { Text("Browse models") }
                if (!d.builtIn && !isNew) TextButton(onClick = onDelete) { Text("Delete", color = skin.error) }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && desc.isNotBlank(), onClick = { onSave(d.copy(name = name.trim(), description = desc.trim(), instructions = instr.trim(), model = model)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pick) ModelPickerDialog(rt, model, onPick = { model = it.id; pick = false }, onDismiss = { pick = false })
}
