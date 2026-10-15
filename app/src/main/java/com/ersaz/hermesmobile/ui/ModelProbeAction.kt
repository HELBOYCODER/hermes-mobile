package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.llm.ProbeResult
import com.ersaz.hermesmobile.llm.ProbeStatus
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ModelProbeAction(rt: HermesRuntime, profile: ModelProfile) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var checking by remember(profile.id) { mutableStateOf(false) }
    var result by remember(profile.id) { mutableStateOf<ProbeResult?>(null) }
    var error by remember(profile.id) { mutableStateOf(false) }
    var confirm by remember(profile.id) { mutableStateOf(false) }
    val test = {
        checking = true; error = false
        scope.launch {
            try { result = rt.modelProbe.test(profile) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { checking = false }
        }
        Unit
    }
    Column {
        TextButton(enabled = !checking, onClick = { if (!profile.free) confirm = true else test() }) {
            Text(if (checking) "Testing…" else "Test")
        }
        result?.let { r -> Text("${r.status.name.lowercase().replace('_', ' ')} · ${r.latencyMs}ms",
            color = if (r.status == ProbeStatus.RESPONDED) skin.ok else skin.warn, fontSize = 10.sp) }
        if (error) Text("Test unavailable", color = skin.warn, fontSize = 10.sp)
    }
    if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Test model") }, text = { Text("Sends a small request. Provider credit may be used. Chat history is not sent.") },
        confirmButton = { TextButton(onClick = { confirm = false; test() }) { Text("Test") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
