package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/**
 * Z.ai account login — STUB, intentionally. Checked 2026-10-30 against the
 * official Z.ai developer documentation (docs.z.ai) and ZCode docs
 * (zcode.z.ai): the official docs publish API-key creation in the
 * developer console (standard keys at https://api.z.ai/api/paas/v4, Coding
 * Plan keys at https://api.z.ai/api/coding/paas/v4 for OpenAI-compatible
 * coding clients) and describe in-client "authorize and sign in" inside
 * ZCode itself, but they publish NO third-party OAuth authorize/token
 * endpoint, client_id, or scope that Hermes could use. Inventing one
 * would send the user's credentials to an unverified URL, so this card
 * sends NOTHING anywhere: no URL is opened, no token is created, logged,
 * or stored by this button. The supported Z.ai path today is the
 * existing API-key preset (Add provider → z.ai → the user's own key);
 * once a real official endpoint is verified, the one-tap flow
 * (system-browser authorize URL + hermes-auth return link, exactly like
 * BrowserAuthCoordinator, then key provisioning into the existing
 * provider store) belongs here, behind this same card. Until then the
 * label says so plainly, and Z.ai models fetched with the user's own
 * key appear in the ModelPicker exactly like Nous models.
 */
@Composable
fun ZaiLoginCard(rt: HermesRuntime) {
    val skin = LocalSkin.current
    var showWhy by remember { mutableStateOf(false) }
    // rt is kept for API symmetry with NousLoginCard(rt); the stub itself
    // deliberately touches no runtime state, token store, or network.
    Card {
        Text("Z.ai account", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Official login (unverified endpoint — needs checking). Automatic sign-in is not available yet: Z.ai's official docs publish no third-party OAuth endpoint for Hermes to use, so this button provisions nothing and sends no credential anywhere.",
            color = skin.warn, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedButton(onClick = { showWhy = !showWhy }) { Text(if (showWhy) "Hide details" else "Why is this not a login button?") }
        if (showWhy) {
            Column(Modifier.padding(top = 6.dp)) {
                Text(
                    "What was checked (2026-10-30): docs.z.ai describes API-key creation only — a standard key for https://api.z.ai/api/paas/v4 and a separate Coding Plan key for https://api.z.ai/api/coding/paas/v4 (the OpenAI-compatible coding endpoint; the two key types are not interchangeable). ZCode's own docs describe account sign-in inside ZCode, not a reusable authorize URL for third-party apps. No client_id, scope, or token endpoint for Hermes was found in either source, so none is guessed here.",
                    color = skin.text, fontSize = 12.5.sp,
                )
                Text(
                    "Use this instead today: Add provider → preset z.ai → enter your own key (from your Z.ai console). Models fetched with that key appear in the ModelPicker like Nous models. Never paste a key into chat, and never use a shared account.",
                    color = skin.text, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
