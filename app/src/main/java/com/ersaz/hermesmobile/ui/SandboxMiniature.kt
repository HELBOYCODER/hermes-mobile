package com.ersaz.hermesmobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.delay

/**
 * THE sandbox miniature, in Hermes' own design. It is deliberately tiny and
 * conditional: it exists ONLY while there is live sandbox/terminal activity in
 * this session (a running process, a running terminal/coding tool call, or an
 * in-progress todo), and it disappears the moment that activity ends.
 * It never covers the composer (it sits in the column above it, width-capped), it is
 * dismissible per activity, and tapping it opens the full Terminal view.
 * 2.7.2: collapsed it is ONE tiny line — status dot + short command only, no
 * terminal output lines at all. Tapping expand reveals the output (8 lines);
 * collapsing hides it again. What it shows is real, read from the live process
 * registry: a status dot, todo progress (completed/total) when the session has
 * todos, and — only when expanded — terminal output redacted exactly like the
 * full Terminal screen (environment redaction + API-key masking). Decorative
 * animation is gated by Settings → Chat animations and is paused by
 * construction when nothing is running, since the miniature is not on screen then.
 */
@Composable
fun SandboxMiniPreview(vm: ChatViewModel, onOpenTerminal: () -> Unit) {
    val skin = LocalSkin.current
    val rt = vm.runtime
    val anim = rt.settings.chatAnimations
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refreshRead = refresh

    val running = rt.processes.list().lastOrNull { it.isRunning }
    val activeTool = vm.items.lastOrNull { it is ChatItem.Tool && it.result == null &&
        (it.name.equals("terminal", true) || it.name.equals("coding_workflow", true)) } as? ChatItem.Tool
    val todos = vm.agent.session.todos
    val doneTodos = todos.count { it.status == "completed" }
    val inProgressTodos = todos.any { it.status == "in_progress" }

    // Live-activity gate: nothing running, no in-progress todo, no miniature. A fresh chat
    // therefore shows no miniature at all.
    val activityKey = running?.id ?: activeTool?.key ?: if (vm.busy && inProgressTodos) "todos" else null
    val visible = activityKey != null
    var dismissed by remember(activityKey) { mutableStateOf(false) }
    var expanded by remember(activityKey) { mutableStateOf(false) }

    // Poll the process registry only while a turn is running (never on chat open, never idle).
    LaunchedEffect(vm.busy, vm.items.size) {
        while (vm.busy) {
            delay(2300)
            refresh++
        }
    }

    if (!visible || dismissed) return
    val body: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(16.dp), color = skin.surface, shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenTerminal),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .then(if (anim) Modifier.animateContentSize() else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Collapsed: ONE tiny line — dot + short command only.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotAlpha = if (anim) {
                        val t = rememberInfiniteTransition(label = "sandbox-dot")
                        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
                    } else 1f
                    Box(Modifier.size(8.dp).alpha(dotAlpha).background(skin.ok, CircleShape))
                    val statusText = when {
                        running != null -> "running · ${running.command.take(32)}"
                        activeTool != null -> "agent running ${activeTool.name}"
                        else -> "tasks in progress"
                    }
                    Text(statusText, color = skin.dim, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (todos.isNotEmpty()) Text("$doneTodos/${todos.size} tasks", color = skin.dim, fontSize = 11.sp)
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse" else "Expand", tint = skin.dim)
                    }
                    IconButton(onClick = { dismissed = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = skin.dim)
                    }
                }
                // Terminal output ONLY when expanded — never on the collapsed line.
                if (expanded && running != null) {
                    val raw = rt.processes.log(running.id, 8).orEmpty()
                    val redacted = rt.settings.redactEnvironment(raw).replace(Regex("(?i)(api[_-]?key|token|secret)[=:][^\\s]+"), "$1=***")
                    Text(redacted.ifBlank { "(no output yet)" }, color = skin.text, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 8,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (anim) {
        AnimatedVisibility(visible = true, enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) { body() }
    } else body()
}
