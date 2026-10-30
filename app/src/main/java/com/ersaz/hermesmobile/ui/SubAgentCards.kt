package com.ersaz.hermesmobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.agent.jobs.AgentJob
import com.ersaz.hermesmobile.agent.jobs.JobState
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/**
 * Sub-agent cards, in Hermes' own design, driven by the real AgentJobs engine
 * (agent/jobs/SubAgents.kt — at most 3 run concurrently, the rest queue).
 * A card exists ONLY while its sub-agent is active (QUEUED/RUNNING) or, for finished ones,
 * until the user dismisses it — finished results also arrive as a chat message, so a
 * finished card is a convenience, not the only record. Each card shows the task, the tool
 * currently running, and the latest output; active cards offer real Steer and Stop actions
 * (AgentJobs.steer / AgentJobs.cancel — Steer delivers into the running child session,
 * Stop interrupts it). Nothing here is simulated: state comes from AgentJobs.tick, so a
 * card updates the moment the engine changes state.
 */
@Composable
fun SubAgentCards(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val rt = vm.runtime
    val anim = rt.settings.chatAnimations
    val tick by rt.agentJobs.tick.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tickRead = tick
    val jobs = remember(tick) { rt.agentJobs.forSession(vm.agent.session.id) }
    val dismissed = remember { mutableStateListOf<String>() }
    val active = jobs.filter { !it.state.finished }
    val finished = jobs.filter { it.state.finished && it.id !in dismissed }.takeLast(3)
    if (active.isEmpty() && finished.isEmpty()) return
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        active.forEach { SubAgentCard(it, rt, anim, onDismiss = {}) }
        finished.forEach { SubAgentCard(it, rt, anim, onDismiss = { dismissed.add(it.id) }) }
    }
}

@Composable
private fun SubAgentCard(job: AgentJob, rt: com.ersaz.hermesmobile.HermesRuntime, anim: Boolean, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val isActive = !job.state.finished
    var expanded by remember(job.id) { mutableStateOf(isActive) }
    var steerField by remember(job.id) { mutableStateOf(false) }
    var steerText by remember(job.id) { mutableStateOf("") }
    var steerNote by remember(job.id) { mutableStateOf("") }
    val dotColor = when (job.state) {
        JobState.QUEUED -> skin.warn
        JobState.RUNNING -> skin.ok
        JobState.DONE -> skin.ok
        JobState.CANCELLED, JobState.INTERRUPTED -> skin.dim
        JobState.FAILED, JobState.TIMEOUT -> skin.error
    }
    val dotAlpha = if (anim && isActive) {
        val t = rememberInfiniteTransition(label = "subagent-dot")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Surface(
        shape = RoundedCornerShape(16.dp), color = skin.surface, shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(if (anim) Modifier.animateContentSize() else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).alpha(dotAlpha).background(dotColor, CircleShape))
                Text(job.agentName, color = skin.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp))
                Text(" · ${job.state.name.lowercase()} · ${job.elapsedSec}s", color = skin.dim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (!isActive) IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = skin.dim)
                }
            }
            Text(job.task, color = skin.text, fontSize = 13.sp, maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis)
            if (job.currentTool.isNotBlank() && isActive) {
                Text("Tool: ${job.currentTool}", color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (job.model.isNotBlank()) Text(job.model, color = skin.label, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { steerField = !steerField }) { Text("Steer", fontSize = 13.sp) }
                    TextButton(onClick = { rt.agentJobs.cancel(job) }) { Text("Stop", fontSize = 13.sp, color = skin.error) }
                }
                if (steerField) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = steerText, onValueChange = { steerText = it },
                            placeholder = { Text("Steer this sub-agent…", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val ok = rt.agentJobs.steer(job, steerText)
                            steerNote = if (ok) "Steer sent." else "Job already finished — steer not applied."
                            steerText = ""; steerField = false
                        }, enabled = steerText.isNotBlank()) { Text("Send", fontSize = 13.sp) }
                    }
                }
                if (steerNote.isNotBlank()) Text(steerNote, color = skin.dim, fontSize = 11.sp)
            } else {
                Row {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide result" else "Show result", fontSize = 13.sp)
                    }
                }
                if (expanded && job.result.isNotBlank()) {
                    Text(job.result.take(800), color = skin.text, fontSize = 12.sp, maxLines = 8,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { expanded = false })
                }
            }
        }
    }
}
