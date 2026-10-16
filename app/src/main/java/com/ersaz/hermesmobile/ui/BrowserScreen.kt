package com.ersaz.hermesmobile.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The agent's browser, on screen: watch it work, or take over to sign in / solve a CAPTCHA. */
@Composable
fun BrowserScreen(rt: HermesRuntime, onExit: () -> Unit = {}) {
    val skin = LocalSkin.current
    val b = rt.browser
    val scope = rememberCoroutineScope()
    val handover by b.handover.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(700); tick++ } }
    var urlText by remember { mutableStateOf("") }
    val tabs = remember(tick) { b.tabList }
    val active = remember(tick) { b.active }
    LaunchedEffect(active?.id) { if (active == null) b.newTab(null) }
    LaunchedEffect(active?.url) { active?.url?.let { urlText = it } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Return to previous Hermes screen", tint = skin.label)
            }
            Text("Browser · Manual control", modifier = Modifier.weight(1f), color = skin.title)
            if (handover != null) Button(onClick = { b.finishHandover(); onExit() }) { Text("Done") }
        }
        handover?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🤝 $msg", color = skin.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Complete the verification in the page, then tap Done above.", color = skin.label, fontSize = 12.sp)
            }
        }
        LazyRow(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(tabs, key = { it.id }) { t ->
                FilterChip(selected = t.id == active?.id, onClick = { b.switchTo(t.id) },
                    label = { Text(t.title.ifBlank { t.url }.take(18)) },
                    trailingIcon = { Icon(Icons.Default.Close, "close", Modifier.clickable { scope.launch { b.close(t.id) } }) })
            }
            item { IconButton(onClick = { scope.launch { b.newTab(null) } }) { Icon(Icons.Default.Add, "new tab", tint = skin.accent) } }
        }
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { active?.let { t -> scope.launch { b.back(t) } } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = skin.label) }
            IconButton(onClick = { active?.let { t -> scope.launch { b.forward(t) } } }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "forward", tint = skin.label) }
            IconButton(onClick = { active?.let { t -> scope.launch { b.reload(t) } } }) { Icon(Icons.Default.Refresh, "reload", tint = skin.label) }
            OutlinedTextField(
                value = urlText, onValueChange = { urlText = it }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { active?.let { t -> scope.launch { runCatching { b.navigate(t, urlText) } } } }),
            )
        }
        val a = active
        if (a != null) {
            // The page lives in the shared WebView; hand it to this screen and give it back when we leave.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { frame ->
                    val web = b.viewFor(a)
                    if (web.parent !== frame) {
                        (web.parent as? ViewGroup)?.removeView(web)
                        frame.removeAllViews()
                        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                onRelease = { frame -> frame.removeAllViews(); b.release(a) },
            )
            DisposableEffect(a.id) { onDispose { b.release(a) } }
        }
    }
}
