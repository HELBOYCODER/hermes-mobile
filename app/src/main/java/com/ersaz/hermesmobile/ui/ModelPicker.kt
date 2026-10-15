package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.llm.ModelInfo
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.launch

/** Bottom sheet in chat: every provider/model that was added, pick one to switch, add or fetch more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val rt = vm.runtime
    val store = rt.modelStore
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var freeOnly by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    remember { store.ensureCurrent(Providers.byId(rt.settings.provider).label); 0 }

    LaunchedEffect(Unit) {
        if (rt.nousAuth.signedIn) {
            runCatching { rt.refreshNousModels() }
                .onFailure { msg = "Could not refresh Nous catalog: ${it.message}" }
            rev++
        }
    }

    val list = remember(rev, freeOnly, query) { store.all().filter { (!freeOnly || it.free) && (query.isBlank() || it.model.contains(query, true) || it.label.contains(query, true)) }.sortedWith(compareBy({ it.label }, { it.model })) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = skin.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Models", color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search models or providers") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !freeOnly, onClick = { freeOnly = false }, label = { Text("All") })
                FilterChip(selected = freeOnly, onClick = { freeOnly = true }, label = { Text("Free") })
            }
            if (list.isEmpty()) Text("No models yet. Fetch the current provider's list or add one.", color = skin.dim, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                var last = ""
                list.forEach { p ->
                    if (p.label != last) { last = p.label; item(key = "h_" + p.label) { Text(p.label, color = skin.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) } }
                    item(key = p.id) {
                        val active = store.isActive(p)
                        Row(
                            Modifier.fillMaxWidth().clickable { store.select(p); vm.modelChanged(); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.model, color = if (active) skin.title else skin.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                Text(if (p.free) "FREE" else p.priceLabel.ifBlank { "Pricing not listed" },
                                    color = if (p.free) skin.ok else skin.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            ModelProbeAction(rt, p)
                            if (active) Icon(Icons.Default.Check, "selected", tint = skin.accent, modifier = Modifier.size(20.dp))
                            IconButton(onClick = { store.remove(p.id); rev++ }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "remove from list", tint = skin.dim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (msg.isNotBlank()) Text(msg, color = skin.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    msg = "Fetching…"
                    scope.launch {
                        val s = rt.settings
                        if (s.provider == "9router") {
                            msg = if (rt.router.refreshFreeModels(false)) "OpenCode Free models refreshed." else rt.router.message.value
                            rev++
                        } else {
                            runCatching { if (s.provider == "nous") rt.refreshNousModels() else rt.llmClient().listModelInfo() }.fold(
                                onSuccess = { models ->
                                    val label = Providers.byId(s.provider).label
                                    if (s.provider != "nous") store.upsertAll(models.map {
                                        ModelProfile(label, s.provider, s.baseUrl, s.apiKey, it.id, it.free, "fetched", it.priceLabel())
                                    })
                                    msg = "Loaded ${models.size} models from $label."; rev++
                                },
                                onFailure = { msg = "Could not fetch: ${it.message}" },
                            )
                        }
                    }
                }) { Text("Fetch from provider") }
                OutlinedButton(onClick = { adding = true }) { Text("Add model") }
            }
            Spacer(Modifier.size(20.dp))
        }
    }

    if (adding) AddModelDialog(onDismiss = { adding = false }) { p -> store.upsert(p); store.select(p); vm.modelChanged(); adding = false; onDismiss() }
}

@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onAdd: (ModelProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider / model") },
        text = {
            Column {
                Field("Provider name", name) { name = it }
                Field("Base URL (…/v1)", url) { url = it.trim() }
                Field("API key (optional)", key, secret = true) { key = it.trim() }
                Field("Model id", model) { model = it.trim() }
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && model.isNotBlank(), onClick = {
                onAdd(ModelProfile(name.ifBlank { url.substringAfter("://").substringBefore('/') }, "custom", url.trimEnd('/'), key, model))
            }) { Text("Add & use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Searchable model list for the current provider; free and tool-capable models are marked. */
@Composable
fun ModelPickerDialog(rt: HermesRuntime, current: String, onPick: (ModelInfo) -> Unit, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { models = if (rt.settings.provider == "nous") rt.refreshNousModels() else rt.llmClient().listModelInfo() } catch (e: Exception) { error = e.message ?: e.toString(); models = emptyList() }
    }
    val shown = remember(models, query, freeOnly) {
        (models ?: emptyList())
            .filter { (!freeOnly || it.free) && (query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true)) }
            .sortedWith(compareByDescending<ModelInfo> { it.free && freeOnly }.thenBy { it.id })
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
