package com.ersaz.hermesmobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.router.NineRouter
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.launch

/**
 * Top-level 9Router section (drawer group "AI router"). The detail controls
 * in Settings → Local 9Router stay as-is; this screen is the default home
 * for the router: state, install/start/stop, free-model refresh, dashboard
 * access and wreq-js status. It reuses the router's state/log/message flows
 * and the existing dashboard password — it never stores a second copy.
 */
@Composable
fun RouterScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val rState by rt.router.state.collectAsState()
    val rMsg by rt.router.message.collectAsState()
    val rLog by rt.router.log.collectAsState()
    val wreq by rt.router.wreqJsStatus.collectAsState()
    val rDiagnosis by rt.router.diagnosis.collectAsState()
    var rev by remember { mutableIntStateOf(0) }
    var showPassword by remember { mutableStateOf(false) }
    var diagText by remember { mutableStateOf("") }
    var diagBusy by remember { mutableStateOf(false) }
    val s = rt.settings
    // Recompute on every action; the store itself is the source of truth.
    @Suppress("UNUSED_VARIABLE")
    val refreshTick = rev
    val freeCount = remember(refreshTick) { rt.modelStore.all().count { it.source == "9router" } }
    val busy = rState == NineRouter.State.INSTALLING || rState == NineRouter.State.STARTING

    LaunchedEffect(Unit) {
        if (rt.router.installed()) scope.launch { runCatching { rt.router.checkWreqJs() } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("9Router", color = skin.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("Your local AI router. Installed by default and started with the app; it serves OpenCode Free models to Hermes on this phone.",
            color = skin.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        Text("Status: " + when (rState) {
            NineRouter.State.NOT_INSTALLED -> "not installed"
            NineRouter.State.INSTALLING -> "installing…"
            NineRouter.State.STOPPED -> "installed, stopped"
            NineRouter.State.STARTING -> "starting…"
            NineRouter.State.RUNNING -> "running at ${rt.router.apiUrl}"
            NineRouter.State.ERROR -> "error"
        }, color = skin.label, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        Text("Free models listed: $freeCount", color = skin.label, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        // Honest empty states: a zero here used to look identical whether the router was down,
        // the dashboard login failed, the catalog was empty, or the quota was hit. Each cause
        // now gets its own line and its own next step.
        if (freeCount == 0) Text(when (rState) {
            NineRouter.State.NOT_INSTALLED -> "No free models yet — 9Router is not installed. Tap Install 9Router below."
            NineRouter.State.STOPPED, NineRouter.State.STARTING, NineRouter.State.INSTALLING -> "No free models yet — 9Router is not running. Tap Start, then Refresh free models."
            NineRouter.State.ERROR -> "No free models listed — the last setup step failed (see the message below or tap Diagnose free models)."
            NineRouter.State.RUNNING -> "9Router is running but lists no OpenCode free models. Tap Diagnose free models to see which catalog step came back empty, then Refresh free models."
        }, color = skin.warn, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 2.dp))
        Text("wreq-js: $wreq", color = if (wreq == "available") skin.ok else skin.label, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        if (wreq != "available") Text(
            "wreq-js lets 9Router's OpenCode free lane resolve its runtime dependency. Installing it fixes the 502 “wreq-js unavailable” error when a free model is CALLED; it does not fill this list (a missing wreq-js never empties the catalog) and it does not remove OpenCode's upstream free-tier daily quota.",
            color = skin.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(enabled = !busy, onClick = { scope.launch { rt.router.installAndSetup(); rev++ } }) {
                Text(if (rt.router.installed()) "Set up & select" else "Install 9Router")
            }
            if (rState == NineRouter.State.RUNNING) {
                OutlinedButton(onClick = { scope.launch { rt.router.stop(); rev++ } }) { Text("Stop") }
            } else if (rt.router.installed()) {
                OutlinedButton(enabled = !busy, onClick = { scope.launch { rt.router.start(); rev++ } }) { Text("Start") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(enabled = rState == NineRouter.State.RUNNING,
                onClick = { scope.launch { rt.router.refreshFreeModels(selectAsProvider = false); rev++ } }) { Text("Refresh free models") }
            OutlinedButton(enabled = !diagBusy, onClick = {
                diagBusy = true; scope.launch {
                    diagText = rt.router.diagnose()
                    diagBusy = false; rev++
                }
            }) { Text(if (diagBusy) "Diagnosing…" else "Diagnose free models") }
            OutlinedButton(onClick = { scope.launch { rt.router.ensureWreqJs(); rev++ } }) { Text("Ensure wreq-js") }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rt.router.dashboardUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Dashboard") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Dashboard", color = skin.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(rt.router.dashboardUrl, color = skin.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(rt.router.dashboardUrl)) }) { Text("Copy URL") }
        }
        // Same password value as Settings → Local 9Router; shown masked by default.
        Field("Dashboard password", if (showPassword) s.routerPassword else "•".repeat(s.routerPassword.length.coerceAtMost(20)), secret = false, readOnly = true) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide password" else "Show password") }
            OutlinedButton(enabled = s.routerPassword.isNotBlank(), onClick = {
                clipboard.setText(AnnotatedString(s.routerPassword))
            }) { Text("Copy password") }
        }
        if (s.routerPassword.isBlank()) Text("The dashboard password appears when 9Router starts.", color = skin.dim, fontSize = 12.sp)
        Toggle("Start with the app", s.routerAutoStart, "Starts 9Router whenever Hermes opens") { s.routerAutoStart = it; rev++ }
        if (diagText.isNotBlank()) Text(diagText, color = skin.label, fontSize = 12.5.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(skin.surface, RoundedCornerShape(10.dp)).padding(10.dp))
        if (rDiagnosis.isNotBlank()) Text(rDiagnosis, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
        if (rMsg.isNotBlank()) Text(rMsg, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
        if (rLog.isNotBlank()) Text(rLog.takeLast(1200), color = skin.dim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(skin.surface, RoundedCornerShape(10.dp)).padding(8.dp))
        Spacer(Modifier.height(32.dp))
    }
}
