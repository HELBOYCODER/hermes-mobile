package com.ersaz.hermesmobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ersaz.hermesmobile.sandbox.GitHubLink
import com.ersaz.hermesmobile.BuildConfig
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Personalities
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import com.ersaz.hermesmobile.ui.theme.Skins
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val s = rt.settings
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMsg by remember { mutableStateOf("") }
    var ghMsg by remember { mutableStateOf("") }
    var perm by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perm++; refresh++ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Full access")
        Hint("Hermes works best with nothing in its way. Grant these once and it can read and write your files, run any command, " +
            "use the mic and keep working in the background.")
        Toggle("Run commands without asking", s.approvalMode == "off",
            "Skips the dangerous-command prompt. Turn off if you want a confirmation every time.") {
            s.approvalMode = if (it) "off" else "manual"; refresh++
        }
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val filesOk = android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        val installOk = ctx.packageManager.canRequestPackageInstalls()
        fun open(intent: Intent) { runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        val pkg = Uri.parse("package:${ctx.packageName}")
        key(perm) {
            PermissionRow("All files access", "Read/write anywhere on phone storage (/sdcard)", filesOk) {
                s.bindSharedStorage = true
                open(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
            }
            PermissionRow("Microphone", "Voice typing in chat", micOk) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            PermissionRow("Notifications", "Results of scheduled tasks and background jobs", notifOk) {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermissionRow("Run in background", "Don't let Android freeze Hermes (bots, cron)", batteryOk) {
                open(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
            PermissionRow("Install apps", "Lets the agent install an APK it built or downloaded", installOk) {
                open(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
            }
        }

        SectionTitle("GitHub")
        Hint("Paste a personal access token (github.com → Settings → Developer settings → Tokens; scopes: repo, workflow, gist, read:org). " +
            "It stays on this phone. Once connected, git in the terminal is already signed in (clone, commit, push) and the agent can use the GitHub API, " +
            "open pull requests and manage issues for you.")
        key(refresh) {
            Field("Personal access token", s.githubToken, secret = true) { s.githubToken = it.trim() }
            Field("GitHub username", s.githubUser) { s.githubUser = it.trim() }
            Field("Commit author name", s.githubName) { s.githubName = it.trim() }
            Field("Commit author email", s.githubEmail) { s.githubEmail = it.trim() }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (s.githubToken.isBlank()) { ghMsg = "Paste a token first."; return@Button }
                ghMsg = "Checking…"
                scope.launch {
                    GitHubLink.verify(rt.http, s.githubToken).fold(
                        onSuccess = { acc ->
                            if (s.githubUser.isBlank()) s.githubUser = acc.login
                            if (s.githubName.isBlank()) s.githubName = acc.name
                            if (s.githubEmail.isBlank()) s.githubEmail = acc.email
                            GitHubLink.apply(rt.sandbox, s)
                            ghMsg = "Connected as ${acc.login}. git is signed in."
                            refresh++
                        },
                        onFailure = { ghMsg = "Failed: ${it.message}" },
                    )
                }
            }) { Text("Save & connect") }
            OutlinedButton(onClick = {
                s.githubToken = ""; scope.launch { GitHubLink.apply(rt.sandbox, s) }; ghMsg = "Disconnected."; refresh++
            }) { Text("Disconnect") }
        }
        if (ghMsg.isNotBlank()) Text(ghMsg, color = if (ghMsg.startsWith("Failed")) skin.error else skin.label, fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 6.dp))

        SectionTitle("🤖 Model provider")
        Hint("Any OpenAI-compatible endpoint works. Nous Portal and OpenRouter give access to Hermes models; local servers (Ollama, LM Studio) on your LAN work too.")
        Picker("Provider", Providers.all.map { it.id to it.label }, s.provider) { id ->
            val p = Providers.byId(id)
            s.provider = id
            if (p.baseUrl.isNotBlank()) s.baseUrl = p.baseUrl
            if (p.defaultModel.isNotBlank()) s.model = p.defaultModel
            refresh++
        }
        key(refresh) {
            Field("Base URL", s.baseUrl) { s.baseUrl = it.trim() }
            Field("API key (${Providers.byId(s.provider).keyHint})", s.apiKey, secret = true) { s.apiKey = it.trim() }
            Field("Model", s.model) { s.model = it.trim() }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                modelMsg = "Loading…"
                scope.launch {
                    models = runCatching { rt.llmClient().listModels() }.getOrElse { modelMsg = "Failed: ${it.message}"; emptyList() }
                    if (models.isNotEmpty()) modelMsg = "${models.size} models"
                }
            }) { Text("Fetch models") }
            Text("  $modelMsg", color = skin.dim, fontSize = 12.sp)
        }
        if (models.isNotEmpty()) {
            Picker("Pick model", models.take(400).map { it to it }, s.model) { s.model = it; refresh++ }
        }
        Field("Auxiliary model (titles, compression; blank = main)", s.auxModel) { s.auxModel = it.trim() }
        Field("Context length (tokens)", s.contextLength.toString(), number = true) { it.toIntOrNull()?.let { v -> s.contextLength = v } }
        Field("Max tool iterations per turn", s.maxIterations.toString(), number = true) { it.toIntOrNull()?.let { v -> s.maxIterations = v } }
        Field("Max output tokens (0 = default)", s.maxTokens.toString(), number = true) { it.toIntOrNull()?.let { v -> s.maxTokens = v } }
        Field("Auto-compress at % of context", s.compressionThreshold.toString(), number = true) { it.toIntOrNull()?.let { v -> s.compressionThreshold = v } }
        Picker("Reasoning effort", listOf("" to "default", "none" to "none", "low" to "low", "medium" to "medium", "high" to "high"), s.reasoningEffort) { s.reasoningEffort = it; refresh++ }
        Toggle("Show reasoning", s.showReasoning) { s.showReasoning = it; refresh++ }

        SectionTitle("🎭 Personality & skin")
        Picker("Personality", listOf("" to "none") + Personalities.BUILTIN.keys.map { it to it }, s.personality) { s.personality = it; refresh++ }
        Picker("Skin", Skins.all.map { it.name to "${it.name} — ${it.description}" }, s.skin) { s.skin = it; refresh++ }
        Field("Extra system prompt", s.customSystemPrompt, singleLine = false) { s.customSystemPrompt = it }
        Picker("Tool progress", listOf("off" to "off", "new" to "new", "all" to "all", "verbose" to "verbose"), s.toolProgress) { s.toolProgress = it; refresh++ }
        Toggle("Status bar", s.showStatusBar) { s.showStatusBar = it; refresh++ }

        SectionTitle("🧰 Toolsets")
        val disabled = s.disabledToolsetSet()
        rt.tools.toolsets.forEach { t ->
            Toggle(t.name, t.name !in disabled, t.description) { s.setToolsetEnabled(t.name, it); refresh++ }
        }
        Picker("Dangerous-command approvals", listOf("manual" to "manual (ask)", "off" to "off (YOLO)"), s.approvalMode) { s.approvalMode = it; refresh++ }
        Field("Command timeout (s)", s.commandTimeout.toString(), number = true) { it.toIntOrNull()?.let { v -> s.commandTimeout = v } }
        Picker("Web search", listOf("duckduckgo" to "DuckDuckGo (no key)", "tavily" to "Tavily", "brave" to "Brave"), s.webSearchBackend) { s.webSearchBackend = it; refresh++ }
        if (s.webSearchBackend == "tavily") Field("Tavily API key", s.tavilyKey, secret = true) { s.tavilyKey = it.trim() }
        if (s.webSearchBackend == "brave") Field("Brave API key", s.braveKey, secret = true) { s.braveKey = it.trim() }

        SectionTitle("💻 Sandbox")
        Toggle("Bind phone storage at /sdcard", s.bindSharedStorage, "Needs 'All files access' permission") { on ->
            s.bindSharedStorage = on
            if (on && android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
            }
            refresh++
        }
        Toggle("Disable PRoot seccomp acceleration", s.prootNoSeccomp, "Try this if commands crash on your ROM") { s.prootNoSeccomp = it; refresh++ }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { rt.sandbox.reinstall() } }) { Text("Reinstall rootfs") }
        }
        Text("Rootfs: ${rt.sandbox.rootfs.absolutePath}", color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        SectionTitle("🌐 Remote Hermes WebUI")
        Hint("Optional: the original WebView mode for a self-hosted Hermes WebUI (LAN, Tailscale, or public URL).")
        Field("WebUI URL", s.webUiUrl.ifBlank { BuildConfig.HERMES_URL }) { s.webUiUrl = it.trim() }

        Spacer(Modifier.height(16.dp))
        Text("Hermes Agent for Android ${HermesRuntime.VERSION}\nPort of NousResearch/hermes-agent (MIT). Sandbox: Alpine Linux + PRoot.",
            color = skin.dim, fontSize = 11.sp)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PermissionRow(title: String, hint: String, granted: Boolean, onGrant: () -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = skin.text, fontSize = 15.sp)
            Text(hint, color = skin.dim, fontSize = 12.sp)
        }
        if (granted) Text("Granted", color = skin.ok, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        else Button(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
fun Picker(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val skin = LocalSkin.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = skin.dim, fontSize = 12.sp)
        Text((options.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "—" }) + "  ▾", color = skin.accent, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

// ------------------------------------------------------------------ WebUI (legacy remote mode)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUiScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val url = rt.settings.webUiUrl.ifBlank { BuildConfig.HERMES_URL }
    if (url.isBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("No WebUI URL set", color = skin.title, fontSize = 18.sp)
            Hint("Set it in Settings → Remote Hermes WebUI. The native agent (Chat) works without it.")
        }
        return
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        WebView(c).apply wv@{
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(this@wv, true) }
            val host = Uri.parse(url).host
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == host) return false
                    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Fix from the original app: attachment uploads had no file chooser.
                override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = cb
                    return try { chooser.launch(params!!.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
                }
            }
            setDownloadListener { dl, _, disposition, mime, _ ->
                runCatching {
                    val req = DownloadManager.Request(Uri.parse(dl)).setMimeType(mime)
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(dl))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(dl, disposition, mime))
                    (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                }
            }
            loadUrl(url)
            webView = this
        }
    })
}
