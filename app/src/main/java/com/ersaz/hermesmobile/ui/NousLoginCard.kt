package com.ersaz.hermesmobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.auth.LoginState
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/**
 * "Sign in with Nous Portal": opens the portal in the browser, waits for approval, then picks a model
 * the account can use — paid when subscribed, otherwise the free ones. No API key to copy.
 */
@Composable
fun NousLoginCard(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val state by rt.nousLogin.state.collectAsState()
    val version by rt.settings.version.collectAsState()
    var pickModel by remember { mutableStateOf(false) }
    val signedIn = remember(version) { rt.nousAuth.signedIn }

    Card {
        Text("☤ Nous Portal", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        when (val st = state) {
            is LoginState.AwaitingBrowser -> {
                Text("Approve this sign-in in the browser. Code:", color = skin.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text(st.code.userCode, color = skin.accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThinkingOrbs(OrbMode.Waiting, 34.dp)
                    Text("Waiting for approval…", color = skin.dim, fontSize = 12.sp)
                }
                Row {
                    TextButton(onClick = { openUrl(ctx, st.code.verificationUriComplete) }) { Text("Open page again") }
                    TextButton(onClick = { copy(ctx, st.code.userCode) }) { Text("Copy code") }
                    TextButton(onClick = { rt.nousLogin.cancel() }) { Text("Cancel", color = skin.error) }
                }
            }
            LoginState.Configuring -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ThinkingOrbs(OrbMode.Thinking, 34.dp)
                Text("Signed in — checking which models your account can use…", color = skin.text, fontSize = 13.sp)
            }
            is LoginState.Connected -> {
                Text("✅ ${st.route.message}", color = skin.ok, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Model: ${st.route.model}", color = skin.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                AccountActions(rt, ctx) { pickModel = true }
            }
            is LoginState.Failed -> {
                Text("⚠️ ${st.message}", color = skin.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Row {
                    Button(onClick = { rt.nousLogin.reset(); startLogin(rt, ctx) }) { Text("Try again") }
                    if (signedIn) TextButton(onClick = { rt.nousLogin.reconfigure() }) { Text("Re-check models") }
                }
            }
            LoginState.Idle -> if (signedIn) {
                val tier = when (rt.settings.nousRoute) { "paid" -> "subscription"; "free", "welcome" -> "free tier"; else -> "signed in" }
                Text("Signed in as ${rt.settings.nousAccount.ifBlank { "your Nous account" }} · $tier", color = skin.ok, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text("Model: ${rt.settings.model}", color = skin.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                AccountActions(rt, ctx) { pickModel = true }
            } else {
                Text("Sign in once in your browser — no API key needed. Free accounts get the free models automatically; subscribers get the full catalog.",
                    color = skin.dim, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 6.dp))
                Button(onClick = { startLogin(rt, ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Nous Portal") }
            }
        }
    }
    if (pickModel) ModelPickerDialog(rt, rt.settings.model, onPick = { rt.useModel(it); pickModel = false }, onDismiss = { pickModel = false })
}

@Composable
private fun AccountActions(rt: HermesRuntime, ctx: Context, browse: () -> Unit) {
    val skin = LocalSkin.current
    Row {
        TextButton(onClick = browse) { Text("Browse models") }
        TextButton(onClick = { rt.nousLogin.reconfigure() }) { Text("Re-check access") }
        TextButton(onClick = { rt.nousLogin.logout() }) { Text("Sign out", color = skin.error) }
    }
}

private fun startLogin(rt: HermesRuntime, ctx: Context) = rt.nousLogin.start { url -> openUrl(ctx, url) }

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Nous code", text))
}
