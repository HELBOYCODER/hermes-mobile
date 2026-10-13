package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.llm.ModelInfo
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/** Searchable model list for the current provider; free and tool-capable models are marked. */
@Composable
fun ModelPickerDialog(rt: HermesRuntime, current: String, onPick: (ModelInfo) -> Unit, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(rt.settings.nousRoute == "free" || rt.settings.nousRoute == "welcome") }

    LaunchedEffect(Unit) {
        try { models = rt.llmClient().listModelInfo() } catch (e: Exception) { error = e.message ?: e.toString(); models = emptyList() }
    }
    val shown = remember(models, query, freeOnly) {
        (models ?: emptyList())
            .filter { (!freeOnly || it.free) && (query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true)) }
            .sortedWith(compareByDescending<ModelInfo> { it.free && freeOnly }.thenBy { it.id })
            .take(300)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a model") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search…") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free only") })
                    Text(models?.let { "${shown.size} of ${it.size}" } ?: "Loading…", color = skin.dim, fontSize = 12.sp)
                }
                if (error.isNotBlank()) Text("Couldn't load models: $error", color = skin.error, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, color = if (m.id == current) skin.accent else skin.text, fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (m.free) Text("  FREE", color = skin.ok, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            val extras = listOfNotNull(
                                m.id.takeIf { it != m.name },
                                m.contextLength.takeIf { it > 0 }?.let { "${it / 1000}k ctx" },
                                m.priceLabel().takeIf { it.isNotBlank() && !m.free },
                                "no tools".takeIf { !m.supportsTools },
                            ).joinToString(" · ")
                            Text(extras, color = skin.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Applies a picked model to the app-wide settings (model id + context window). */
fun HermesRuntime.useModel(m: ModelInfo) {
    settings.model = m.id
    if (m.contextLength > 0) settings.contextLength = m.contextLength.coerceAtMost(200_000)
}
