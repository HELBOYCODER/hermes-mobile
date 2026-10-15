package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.coding.*
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CodingPanels(rt: HermesRuntime, vm: ChatViewModel, onChat: () -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var feature by remember { mutableStateOf("001-feature") }
    var message by remember { mutableStateOf("") }
    var checkpoints by remember { mutableStateOf(emptyList<String>()) }
    var tasks by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val root = projectRoot(rt)
                checkpointStore(rt, root).list() to projectTaskStore(rt, root).read().toString(2)
            } }.onSuccess { checkpoints = it.first; tasks = it.second }.onFailure { message = it.message ?: "Select a project first" }
        }
    }
    LaunchedEffect(rt.settings.codingProject) { refresh() }
    SectionTitle("Specification workflow")
    OutlinedTextField(feature, { feature = it }, label = { Text("Feature id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    CodingPhase.entries.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { phase ->
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val artifact = withContext(Dispatchers.IO) { specWorkflow(rt, feature).advance(projectRoot(rt), phase) }
                            vm.input = "Work in ${rt.settings.codingProject}. Use hermes-spec-workflow. Continue ${phase.name.lowercase()} for $feature using ${rt.sandbox.guestPath(artifact)}. Read existing instructions, preserve user edits, and verify acceptance criteria with actual test evidence."
                            onChat()
                        } catch (e: Exception) { message = e.message ?: "Could not prepare specification" }
                        finally { busy = false }
                    }
                }) { Text(phase.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
    }
    SectionTitle("Tasks & verification")
    Text(if (tasks.isBlank()) "Tasks and test evidence appear here when coding_workflow is used." else tasks, color = skin.dim)
    TextButton(onClick = { refresh() }) { Text("Refresh evidence") }
    SectionTitle("File checkpoints")
    Text("File-tool edits inside the selected project are checkpointed. Terminal changes need Git review. Restore stops if a file has changed since the checkpoint.", color = skin.dim)
    checkpoints.take(10).forEach { id ->
        TextButton(enabled = !busy && !vm.busy, onClick = {
            scope.launch {
                preview = withContext(Dispatchers.IO) { runCatching { checkpointStore(rt, projectRoot(rt)).preview(id) }.getOrDefault("Preview unavailable") }
                restore = id
            }
        }) { Text("Review / restore ${id.take(8)}") }
    }
    if (checkpoints.isEmpty()) Text("No checkpoints yet", color = skin.dim)
    if (message.isNotBlank()) Text(message, color = skin.warn)
    restore?.let { id ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text("Restore this checkpoint?") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text("Only matching checkpointed files will be restored. Later edits stop the operation. Preview is limited to 4000 characters per side.")
                Text(preview, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            } },
            confirmButton = { TextButton(enabled = !vm.busy, onClick = {
                restore = null; busy = true
                scope.launch {
                    message = try { withContext(Dispatchers.IO) {
                        when (val r = checkpointStore(rt, projectRoot(rt)).restore(id)) {
                            RestoreResult.Restored -> "Checkpoint restored"
                            is RestoreResult.Conflict -> "Later changes detected: ${r.paths.joinToString()}. Nothing restored."
                        }
                    } } catch (e: Exception) { e.message ?: "Restore unavailable" }
                    finally { busy = false }
                    refresh()
                }
            }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { restore = null }) { Text("Cancel") } })
    }
}
