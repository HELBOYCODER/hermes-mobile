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
fun CodingPanels(rt: HermesRuntime, vm: ChatViewModel, onChat: () -> Unit, onNavigate: (String) -> Unit = {}) {
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
                val evidence = projectTaskStore(rt, root).read()
                val taskList = evidence.getJSONArray("tasks")
                val runList = evidence.getJSONObject("runs")
                val summary = buildString {
                    for (i in 0 until taskList.length()) {
                        val task = taskList.getJSONObject(i)
                        appendLine("${task.optString("status").uppercase()} · ${task.optString("text").take(300)}")
                    }
                    if (runList.length() > 0) appendLine("\nRecent test evidence")
                    runList.keys().asSequence().map { it to runList.getJSONObject(it) }
                        .sortedByDescending { it.second.optLong("at") }.take(10).forEach { (id, run) ->
                            appendLine("${run.optString("status")} · exit ${run.opt("exit_code")} · ${id.take(8)}")
                            appendLine(run.optString("command").take(200))
                            appendLine(run.optString("output").take(500))
                        }
                }
                checkpointStore(rt, root).list() to summary
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
    // Web preview & export (existing Browser screen + DownloadsExporter;
    // no new backend). Preview renders the single index.html document;
    // linked local assets are not served.
    SectionTitle("Web preview & export")
    var exportMsg by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { projectRoot(rt) }
                    val index = java.io.File(root, "index.html")
                    if (!index.isFile) { message = "No index.html in this project yet." }
                    else {
                        val html = withContext(Dispatchers.IO) { index.readText() }
                        rt.browser.previewHtml(html)
                        onNavigate("Browser")
                    }
                } catch (e: Exception) { message = e.message ?: "Preview unavailable" }
                finally { busy = false }
            }
        }) { Text("Preview index.html") }
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                exportMsg = try {
                    val root = withContext(Dispatchers.IO) { projectRoot(rt) }
                    val zip = withContext(Dispatchers.IO) {
                        val tmp = java.io.File(ctx.cacheDir, "${root.name}.zip")
                        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                            root.walkTopDown().filter { it.isFile && ".git" !in it.relativeTo(root).invariantSeparatorsPath.split('/') }.forEach { f ->
                                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                                f.inputStream().use { it.copyTo(z) }
                                z.closeEntry()
                            }
                        }
                        tmp
                    }
                    val uri = com.ersaz.hermesmobile.files.DownloadsExporter.save(ctx, zip)
                    "Exported ${root.name}.zip to Downloads/Hermes ($uri)."
                } catch (e: Exception) { "Export failed: ${e.message}" }
                busy = false
            }
        }) { Text("Export project ZIP") }
    }
    if (exportMsg.isNotBlank()) Text(exportMsg, color = skin.dim)
    // Pi harness (explicit user press = the approval for that run; see PiHarness).
    SectionTitle("Pi harness")
    var piTask by remember { mutableStateOf("") }
    var piOut by remember { mutableStateOf("") }
    var piStatus by remember { mutableStateOf("") }
    OutlinedTextField(piTask, { piTask = it }, label = { Text("Task for Pi (project folder)") }, singleLine = false, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                piStatus = try { com.ersaz.hermesmobile.agent.PiRunner(rt).status() } catch (e: Exception) { "error: ${e.message}" }
                busy = false
            }
        }) { Text("Check Pi") }
        TextButton(enabled = !busy && piTask.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    val root = withContext(Dispatchers.IO) { projectRoot(rt) }
                    val thinking = com.ersaz.hermesmobile.agent.PiHarness.mapThinking(rt.settings.reasoningEffort)
                    // DeepSeek (or any) model selected in Hermes is passed
                    // through to Pi; Pi must itself be configured for that
                    // provider, otherwise the run fails honestly (see above).
                    val out = com.ersaz.hermesmobile.agent.PiRunner(rt).runPrint(piTask, rt.sandbox.guestPath(root.absolutePath), rt.settings.model, thinking)
                    piOut = out ?: "Pi harness not installed — falling back to native Hermes. Install it in Settings → Agent engine, or send this task in chat (native engine)."
                    if (out == null) { vm.input = piTask; onChat() }
                } catch (e: Exception) { piOut = "Pi run failed: ${e.message}. Falling back to native Hermes." }
                finally { busy = false }
            }
        }) { Text("Run with Pi") }
    }
    if (piStatus.isNotBlank()) Text(piStatus, color = skin.dim)
    if (piOut.isNotBlank()) Text(piOut.take(3000), color = skin.dim)
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
