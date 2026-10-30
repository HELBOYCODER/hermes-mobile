package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.ModelCatalog
import com.ersaz.hermesmobile.llm.ModelInfo
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import com.ersaz.hermesmobile.core.OpenCodeCatalog

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
    var addingProvider by remember { mutableStateOf(false) }
    var connectingZen by remember { mutableStateOf(false) }
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
            // Thinking level, in Hermes' own skin: the same reasoningEffort
            // setting the agent already sends (Agent → LlmClient), exposed
            // here next to model selection instead of buried in Settings.
            // Soft, rounded chips — Hermes' own design system, not another app's UI.
            Text("Thinking", color = skin.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf("" to "Default", "none" to "None", "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "XHigh", "max" to "Max").forEach { (value, label) ->
                    FilterChip(
                        selected = rt.settings.reasoningEffort == value,
                        onClick = { rt.settings.reasoningEffort = value; rev++ },
                        label = { Text(label) },
                    )
                }
            }
            // Engine selector (same area as model selection): Hermes native
            // (default), Pi harness, or Copilot (Foundry, remote). Pi runs
            // only if installed; Copilot is a stub until its Foundry
            // invocation surface is verified — selecting it falls back to
            // native, never a fake Copilot run (see CopilotHarness).
            Text("Engine", color = skin.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = rt.settings.engine == "native", onClick = { rt.settings.engine = "native"; rev++ }, label = { Text("Hermes native") })
                FilterChip(selected = rt.settings.engine == "pi", onClick = { rt.settings.engine = "pi"; rev++ }, label = { Text("Pi harness") })
                FilterChip(selected = rt.settings.engine == "copilot", onClick = { rt.settings.engine = "copilot"; rev++ }, label = { Text("Copilot (Foundry)") })
            }
            if (rt.settings.engine == "pi") Text("Pi harness runs in the sandbox via the pi CLI. Install/check it in Settings → Agent engine; if it is not installed, chat falls back to native.",
                color = skin.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            if (rt.settings.engine == "copilot") Text("Copilot (Foundry) — remote engine, public preview. ${com.ersaz.hermesmobile.agent.CopilotHarness.BILLING_NOTE} Needs your own Foundry account (Settings → Agent engine); until its invocation surface is verified this build sends nothing to Foundry and chat runs the native engine.",
                color = skin.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            // Prompting Claude Opus card (Hermes' own summary, not copied
            // text): shown when the current model is Opus-family.
            if (rt.settings.model.contains("opus", ignoreCase = true)) {
                Text("Prompting Claude Opus — Hermes summary", color = skin.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Opus reasons best with room to think: give it the full context up front, ask for step-by-step reasoning before the answer on hard tasks, use extended thinking for multi-step work, be explicit about the output shape, and ask it to verify its answer against the input. Official guidance: docs.anthropic.com (Anthropic docs, linked — not copied here).",
                    color = skin.dim, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search models or providers") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !freeOnly, onClick = { freeOnly = false }, label = { Text("All") })
                FilterChip(selected = freeOnly, onClick = { freeOnly = true }, label = { Text("Free") })
            }
            if (list.isEmpty()) Text(if (freeOnly) "No free models listed. If you use 9Router, open the 9Router screen and tap Refresh free models — OpenCode free models appear there first, never here by guesswork." else "No models yet. Fetch the current provider's list or add one.", color = skin.dim, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            // "Show current model" jump: flat index in the header-interleaved list (headers count).
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val activeFlatIndex = remember(list) {
                var idx = 0; var last = ""; var found = -1
                for (p in list) {
                    if (p.label != last) { last = p.label; idx++ }
                    if (store.isActive(p)) { found = idx; break }
                    idx++
                }
                found
            }
            if (list.isNotEmpty() && activeFlatIndex >= 0) {
                TextButton(onClick = { scope.launch { listState.scrollToItem(activeFlatIndex) } },
                    modifier = Modifier.padding(bottom = 2.dp)) { Text("Show current model", fontSize = 12.sp) }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
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
                OutlinedButton(onClick = { addingProvider = true }) { Text("Add provider") }
                OutlinedButton(onClick = { connectingZen = true }) { Text("OpenCode Zen") }
            }
            Spacer(Modifier.size(20.dp))
        }
    }

    if (connectingZen) OpenCodeZenDialog(
        rt = rt,
        onDismiss = { connectingZen = false },
        onAdd = { p ->
            store.upsert(p); store.select(p); vm.modelChanged(); connectingZen = false; onDismiss()
        },
        onFreeModelsLoaded = { count ->
            rev++
            connectingZen = false
            msg = "Listed $count free-tier OpenCode models. Select a model and tap Test to verify your key; catalog listing alone does not confirm access."
        },
    )
    if (adding) AddModelDialog(onDismiss = { adding = false }) { p -> store.upsert(p); store.select(p); vm.modelChanged(); adding = false; onDismiss() }
    if (addingProvider) AddProviderDialog(rt, onDismiss = { addingProvider = false }) { p ->
        store.select(p); vm.modelChanged(); addingProvider = false; rev++; onDismiss()
    }
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

/**
 * Add an OpenAI-compatible provider with the user's OWN account and key.
 * Presets only fill the backend URL — z.ai, OpenRouter, or a free-form
 * custom URL (e.g. the user's own cline2api-style bridge). There is no
 * provider login/OAuth flow here, no shared account, no extracted tokens
 * and no hardcoded key, and no affiliation with any provider is claimed.
 * "Fetch models" calls GET <baseUrl>/models with the user's key, parses
 * with the existing ModelCatalog, stores via modelStore.replaceSource with
 * a per-provider source id, and lists free models first (freeAgentModels).
 * Keys are stored the same way as every existing provider key: in the
 * app-private model-profile list in SharedPreferences (not encrypted at
 * rest — the same limitation as the existing providers). The key is only
 * ever sent to the backend URL the user entered.
 */
@Composable
private fun AddProviderDialog(rt: HermesRuntime, onDismiss: () -> Unit, onPick: (ModelProfile) -> Unit) {
    var preset by remember { mutableStateOf("zai") }
    var name by remember { mutableStateOf("Z.ai") }
    var url by remember { mutableStateOf("https://api.z.ai/api/paas/v4") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<List<ModelProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // Presets only fill the backend URL; the user always enters their own
    // key. DeepSeek, Gemini, Mistral and Groq are OpenAI-compatible presets
    // already in Providers; z.ai stays API-key only (its account-login flow
    // for third-party apps is unverified, so no z.ai login is implemented).
    val presets = listOf(
        Triple("zai", "Z.ai", "https://api.z.ai/api/paas/v4"),
        Triple("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Triple("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        Triple("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Triple("mistral", "Mistral", "https://api.mistral.ai/v1"),
        Triple("groq", "Groq", "https://api.groq.com/openai/v1"),
        Triple("custom", "Custom", ""),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider (your own account)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Presets only fill the backend URL. Enter your own API key for that provider; free models shown are the ones your key can actually list. Z.ai account login is not implemented (unverified for third-party apps) — use a Z.ai API key from the official Z.ai developer console.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    presets.forEach { (id, label, presetUrl) ->
                        FilterChip(selected = preset == id, onClick = {
                            preset = id; name = label
                            if (presetUrl.isNotBlank()) url = presetUrl
                        }, label = { Text(label) })
                    }
                }
                Field("Provider name", name) { name = it }
                Field("Backend base URL (…/v1 or provider path)", url) { url = it.trim() }
                Field("Your API key", key, secret = true) { key = it.trim() }
                Text("Your key stays in this app's private settings, like other provider keys, and is only sent to the URL above.",
                    color = LocalSkin.current.dim, fontSize = 11.sp)
                if (msg.isNotBlank()) Text(msg, color = LocalSkin.current.label, fontSize = 12.sp)
                if (fetched.isNotEmpty()) {
                    Text("Fetched models — free first. Tap one to use it:", color = LocalSkin.current.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(fetched.size) { i ->
                            val p = fetched[i]
                            Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.model, color = LocalSkin.current.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (p.free) "FREE" else p.priceLabel.ifBlank { "Pricing not listed" },
                                        color = if (p.free) LocalSkin.current.ok else LocalSkin.current.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && url.isNotBlank() && key.isNotBlank(), onClick = {
                busy = true; msg = "Fetching models…"
                scope.launch {
                    try {
                        val base = url.trimEnd('/')
                        val models = LlmClient(rt.http, base, key, preset).listModelInfo()
                        if (models.isEmpty()) { msg = "That backend returned no models for this key."; fetched = emptyList() }
                        else {
                            val source = "provider:$preset"
                            val ordered = ModelCatalog.freeAgentModels(models) + models.filter { it !in ModelCatalog.freeAgentModels(models) }
                            val profiles = ordered.map {
                                ModelProfile(name.ifBlank { preset }, preset, base, key, it.id, it.free, source, it.priceLabel())
                            }
                            rt.modelStore.replaceSource(source, profiles)
                            fetched = profiles
                            val freeCount = profiles.count { it.free }
                            msg = "Loaded ${models.size} models ($freeCount free, shown first)."
                        }
                    } catch (e: Exception) {
                        msg = "Could not fetch: ${e.message}"
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Fetching…" else "Fetch models") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
                                ModelProbeAction(rt, com.ersaz.hermesmobile.core.ModelProfile(
                                    m.name, rt.settings.provider,
                                    if (rt.settings.provider == "nous") {
                                        if (m.id == com.ersaz.hermesmobile.auth.NousSetup.WELCOME_MODEL) com.ersaz.hermesmobile.auth.NousPortal.WELCOME
                                        else com.ersaz.hermesmobile.auth.NousPortal.INFERENCE
                                    } else rt.settings.baseUrl,
                                    rt.settings.apiKey, m.id, m.free, "fetched", m.priceLabel()))
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

/**
 * Official OpenCode Console API integration. Account login in a browser does not issue
 * an OAuth token for third-party Android apps; the user supplies their Console API key.
 * Catalog lookup is public, but actual inference must be tested before claiming access.
 */
@Composable
private fun OpenCodeZenDialog(
    rt: HermesRuntime,
    onDismiss: () -> Unit,
    onAdd: (ModelProfile) -> Unit,
    onFreeModelsLoaded: (Int) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect OpenCode Console") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign in to OpenCode Console, create an API key, and paste it here. The free-tier catalog does not guarantee your account can run a model.")
                TextButton(onClick = {
                    com.ersaz.hermesmobile.auth.BrowserAuthCoordinator.open(rt.app, "https://opencode.ai/console")
                }) { Text("Open official OpenCode Console") }
                Field("Console API key", key, secret = true) { key = it.trim() }
                TextButton(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    message = "Loading official free models..."
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) {
                                val request = Request.Builder().url(OpenCodeCatalog.MODELS_URL).get().build()
                                rt.http.newCall(request).execute().use { r ->
                                    if (!r.isSuccessful) throw IllegalStateException("Catalog HTTP " + r.code)
                                    r.body?.string() ?: throw IllegalStateException("Empty model catalog")
                                }
                            }
                            val ids = OpenCodeCatalog.freeChatIds(response)
                            if (ids.isEmpty()) {
                                message = "The official catalog returned no supported free chat models."
                            } else {
                                rt.modelStore.replaceSource("opencode-catalog", ids.map { id ->
                                    ModelProfile("OpenCode Console · Free", "custom",
                                        OpenCodeCatalog.CHAT_BASE_URL, key, id, true, "opencode-catalog")
                                })
                                onFreeModelsLoaded(ids.size)
                            }
                        } catch (e: Exception) {
                            message = "Cannot load OpenCode models: " + (e.message ?: "connection failed")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Loading..." else "Fetch supported free models") }
                Text("Or add one documented chat-completions model manually:")
                Field("Chat-completions model ID", model) { model = it.trim() }
                Text("Other model families use /responses, /messages or Google-specific endpoints and need separate adapters.")
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && key.isNotBlank() && model.isNotBlank(), onClick = {
                onAdd(ModelProfile("OpenCode Console API", "custom",
                    OpenCodeCatalog.CHAT_BASE_URL, key, model, false, "manual"))
            }) { Text("Save manual model") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
