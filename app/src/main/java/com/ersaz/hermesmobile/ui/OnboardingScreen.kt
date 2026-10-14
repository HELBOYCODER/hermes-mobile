package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/** First run: the mobile equivalent of `hermes setup`. */
@Composable
fun OnboardingScreen(rt: HermesRuntime, done: () -> Unit) {
    val skin = LocalSkin.current
    val s = rt.settings
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(skin.symbol, color = skin.accent, fontSize = 44.sp)
        Text("Welcome to ${skin.agentName}", color = skin.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp))
        SectionTitle("Set up Hermes")
        Hint("Easiest: sign in with your Nous Portal account. Free accounts get free models automatically — no API key needed. Or pick another provider and paste its key; keys stay on this phone.")
        NousLoginCard(rt)
        SectionTitle("…or use an API key")
        Picker("Provider", Providers.all.map { it.id to it.label }, s.provider) { id ->
            val p = Providers.byId(id)
            s.provider = id
            if (!(id == "nous" && rt.nousAuth.signedIn)) {
                if (p.baseUrl.isNotBlank()) s.baseUrl = p.baseUrl
                if (p.defaultModel.isNotBlank()) s.model = p.defaultModel
            }
            refresh++
        }
        key(refresh) {
            Field("Base URL", s.baseUrl) { s.baseUrl = it.trim() }
            if (!rt.usesNousLogin()) Field("API key", s.apiKey, secret = true) { s.apiKey = it.trim() }
            Field("Model", s.model) { s.model = it.trim() }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = done) { Text("Start ☤") }
            TextButton(onClick = done) { Text("Skip for now", color = skin.dim) }
        }
    }
}
