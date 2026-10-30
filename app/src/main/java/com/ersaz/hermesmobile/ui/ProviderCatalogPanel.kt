package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.auth.BrowserAuthCoordinator
import com.ersaz.hermesmobile.auth.OpenRouterAuth
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.LoginKind
import com.ersaz.hermesmobile.llm.ModelCatalog
import com.ersaz.hermesmobile.llm.ProviderCatalog
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.launch

/**
 * The complete provider catalog for the Providers section (Settings →
 * Models, and onboarding). Rules, all enforced here:
 *  - EVERY provider offers its official connection only, with the user's
 *    own account/key. No shared account, no extracted cookies/tokens
 *    from consumer apps, no invented URLs (every base URL is reused
 *    from the app's pre-existing Providers list).
 *  - Tapping an entry SELECTS it and AUTO-FILLS its official backend
 *    base URL — the user never types a backend URL by hand (except for
 *    Custom, where the URL is the whole point).
 *  - The selected entry shows exactly ONE primary action: "Login" for
 *    login providers (Nous card, OpenRouter PKCE), "Fetch free models"
 *    for the local 9Router (no external account), "Save key" for key
 *    providers. After a successful login or key save, models are
 *    fetched AUTOMATICALLY via GET <baseUrl>/models, free first, into
 *    the ModelPicker — no extra manual step.
 *  - Z.ai login is LOGIN_UNVERIFIED: its entry shows key entry only
 *    (plus the labelled stub card), never a working Login button, because
 *    no official third-party OAuth endpoint for Z.ai could be verified.
 */
@Composable
fun ProviderCatalogPanel(rt: HermesRuntime, onChanged: () -> Unit = {}) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val s = rt.settings
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(s.provider) }
    var keyText by remember { mutableStateOf(s.apiKey) }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var orPkce by remember { mutableStateOf<OpenRouterAuth.Pkce?>(null) }
    var orCode by remember { mutableStateOf("") }
    val version by s.version.collectAsState()
    var prevNousSignedIn by remember { mutableStateOf(false) }

    // Nous: after a successful login, fetch its models automatically.
    // Fires on the signed-out → signed-in transition (and once on first
    // sight while already signed in), never in a loop: refreshNousModels
    // writes the model store, not settings, so it cannot retrigger this.
    LaunchedEffect(version) {
        val now = rt.nousAuth.signedIn
        if (selected == "nous" && now && !prevNousSignedIn) {
            runCatching { rt.refreshNousModels() }
                .onSuccess { list -> msg = "Nous models loaded automatically: ${list.size} (free first in the ModelPicker)." }
                .onFailure { e -> msg = "Nous sign-in worked, but the automatic model fetch failed: ${e.message}" }
        }
        prevNousSignedIn = now
    }

    fun select(id: String) {
        val entry = ProviderCatalog.byId(id)
        selected = id
        s.provider = id
        // Nous keeps its signed-in route/model; everything else auto-fills.
        if (!(id == "nous" && rt.nousAuth.signedIn)) {
            if (entry.baseUrl.isNotBlank() && entry.loginKind != LoginKind.CUSTOM) s.baseUrl = entry.baseUrl
            if (entry.defaultModel.isNotBlank()) s.model = entry.defaultModel
        }
        keyText = s.apiKey
        orPkce = null; orCode = ""; msg = ""
        onChanged()
    }

    /** Shared automatic fetch: GET <baseUrl>/models, free first, into the picker. */
    fun autoFetch(baseUrl: String, key: String, entryId: String, label: String) {
        if (busy) return
        busy = true; msg = "Fetching models…"
        scope.launch {
            try {
                val base = baseUrl.trimEnd('/')
                val models = LlmClient(rt.http, base, key, entryId).listModelInfo()
                if (models.isEmpty()) {
                    msg = "That backend returned no models for this key."
                } else {
                    val free = ModelCatalog.freeAgentModels(models)
                    val ordered = free + models.filter { it !in free }
                    rt.modelStore.replaceSource("provider:$entryId", ordered.map {
                        ModelProfile(label, entryId, base, key, it.id, it.free, "provider:$entryId", it.priceLabel())
                    })
                    msg = "Loaded ${models.size} models automatically (${free.size} free, shown first). Open Browse models to pick one."
                }
            } catch (e: Exception) {
                msg = "Could not fetch models: ${e.message}"
            } finally { busy = false }
        }
    }

    Hint("Tap a provider: its official backend URL fills in automatically — you never type one by hand. Then use the single action below it: Login, or Save key. After login or key save, that provider's models load into the ModelPicker automatically, free first.")
    val entry = ProviderCatalog.byId(selected)

    Text("Sign in", color = skin.title, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    ProviderCatalog.all.filter { it.loginKind == LoginKind.LOGIN || it.loginKind == LoginKind.ROUTER }.forEach { e ->
        CatalogRow(e, e.id == selected) { select(e.id) }
    }
    Text("API key", color = skin.title, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    ProviderCatalog.all.filter { it.loginKind == LoginKind.KEY || it.loginKind == LoginKind.LOGIN_UNVERIFIED }.forEach { e ->
        CatalogRow(e, e.id == selected) { select(e.id) }
    }
    Text("Custom", color = skin.title, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    ProviderCatalog.all.filter { it.loginKind == LoginKind.CUSTOM }.forEach { e ->
        CatalogRow(e, e.id == selected) { select(e.id) }
    }

    // ---- The selected entry's single primary action -------------------
    Column(Modifier.padding(top = 10.dp)) {
        Text("${entry.displayName} · ${entry.baseUrl.ifBlank { "(you type the URL below)" }}",
            color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
        if (entry.note.isNotBlank()) Text(entry.note, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        when (entry.id) {
            "nous" -> NousLoginCard(rt)
            "openrouter" -> {
                if (orPkce == null) {
                    Button(enabled = !busy, onClick = {
                        val pkce = OpenRouterAuth.newPkce()
                        orPkce = pkce
                        BrowserAuthCoordinator.open(ctx, OpenRouterAuth.authUrl(pkce))
                    }) { Text("Login with OpenRouter") }
                    Hint("Opens OpenRouter in Chrome. After you approve, OpenRouter shows a one-time authorization code (headless mode — this app has no website for a callback, and an intent:// callback is unverified). Paste that code below; it is not your API key, it is single-use and expires in 10 minutes.")
                } else {
                    Field("Authorization code from OpenRouter", orCode) { orCode = it.trim() }
                    Button(enabled = !busy && orCode.isNotBlank(), onClick = {
                        val pkce = orPkce ?: return@Button
                        busy = true; msg = "Exchanging the code for your key…"
                        scope.launch {
                            try {
                                val key = OpenRouterAuth.exchange(rt.http, orCode, pkce)
                                s.provider = "openrouter"; s.baseUrl = entry.baseUrl; s.apiKey = key; keyText = key
                                orPkce = null; orCode = ""
                                autoFetch(entry.baseUrl, key, "openrouter", "OpenRouter")
                            } catch (e: Exception) {
                                msg = "OpenRouter login failed: ${e.message}"
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "Exchanging…" else "Complete login") }
                    Text("The code itself is never stored — only the API key OpenRouter returns, in the app's existing key store.", color = skin.dim, fontSize = 12.sp)
                }
            }
            "9router" -> {
                Button(enabled = !busy, onClick = {
                    busy = true; msg = "Fetching OpenCode Free models from the local router…"
                    scope.launch {
                        try {
                            val ok = rt.router.refreshFreeModels(selectAsProvider = true)
                            val count = rt.modelStore.all().count { it.source == "9router" }
                            msg = if (ok) "OpenCode Free models loaded automatically: $count free (shown first in the ModelPicker)."
                            else "Router fetch finished but no free model answered just now ($count listed). OpenCode may restrict its free tier; the previous provider was kept."
                        } catch (e: Exception) {
                            msg = "Router fetch failed: ${e.message}"
                        } finally { busy = false }
                    }
                }) { Text("Fetch free models") }
            }
            "zai" -> {
                ZaiLoginCard(rt)
                Field("Your Z.ai API key (${entry.keyHint})", keyText, secret = true) { keyText = it.trim() }
                Button(enabled = !busy && keyText.isNotBlank(), onClick = {
                    s.provider = "zai"; s.baseUrl = entry.baseUrl; s.apiKey = keyText
                    autoFetch(entry.baseUrl, keyText, "zai", "Z.ai")
                }) { Text(if (busy) "Saving…" else "Save key") }
            }
            "custom" -> {
                Field("Provider name", customName) { customName = it }
                Field("Backend base URL", customUrl) { customUrl = it.trim() }
                Field("Your API key", keyText, secret = true) { keyText = it.trim() }
                Button(enabled = !busy && customUrl.isNotBlank() && keyText.isNotBlank(), onClick = {
                    s.provider = "custom"; s.baseUrl = customUrl; s.apiKey = keyText
                    autoFetch(customUrl, keyText, "custom", customName.ifBlank { "Custom" })
                }) { Text(if (busy) "Saving…" else "Save key") }
            }
            else -> {
                Field("Your API key (${entry.keyHint})", keyText, secret = true) { keyText = it.trim() }
                Button(enabled = !busy && (keyText.isNotBlank() || entry.id == "ollama" || entry.id == "lmstudio"), onClick = {
                    s.provider = entry.id; s.baseUrl = entry.baseUrl; s.apiKey = keyText
                    autoFetch(entry.baseUrl, keyText, entry.id, entry.displayName)
                }) { Text(if (busy) "Saving…" else "Save key") }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CatalogRow(e: com.ersaz.hermesmobile.llm.CatalogEntry, isSelected: Boolean, onClick: () -> Unit) {
    val badge = when (e.loginKind) {
        LoginKind.LOGIN -> "Login"
        LoginKind.ROUTER -> "Local · no account"
        LoginKind.LOGIN_UNVERIFIED -> "Unverified — key entry only"
        LoginKind.CUSTOM -> "Custom"
        LoginKind.KEY -> "API key"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isSelected) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("${e.displayName} · $badge")
                if (e.baseUrl.isNotBlank()) Text(e.baseUrl, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        } else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("${e.displayName} · $badge")
                if (e.baseUrl.isNotBlank()) Text(e.baseUrl, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
