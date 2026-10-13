package com.ersaz.hermesmobile.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import com.ersaz.hermesmobile.ui.theme.Skins
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(vm.items.size, (vm.items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.size)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        imageDataUri(context, uri)?.let { vm.pendingImages += it }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            if (vm.items.isEmpty()) item { Banner() }
            items(vm.items, key = { it.key }) { item -> ChatRow(item) }
            if (vm.busy) item { ThinkingIndicator(vm.orbMode(), vm.orbDetail()) }
        }

        if (vm.runtime.settings.showStatusBar) StatusBar(vm)

        // Slash-command autocomplete (Hermes CLI completion menu)
        if (vm.input.startsWith("/") && !vm.input.contains(' ')) {
            val sugg = remember(vm.input) { Commands.suggestions(vm.runtime, vm.input) }
            if (sugg.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(skin.surface)) {
                    items(sugg) { (cmd, desc) ->
                        Row(Modifier.fillMaxWidth().clickable { vm.input = cmd }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(cmd.trim(), color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(130.dp))
                            Text(desc, color = skin.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (vm.pendingImages.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🖼️ ${vm.pendingImages.size} image(s) attached", color = skin.label, fontSize = 13.sp)
                IconButton(onClick = { vm.pendingImages.clear() }) { Icon(Icons.Default.Close, "clear", tint = skin.dim) }
            }
        }

        HorizontalDivider(color = skin.border.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { picker.launch("image/*") }) { Icon(Icons.Default.AddPhotoAlternate, "attach image", tint = skin.label) }
            OutlinedTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                placeholder = { Text("${skin.promptSymbol} Message ${skin.agentName.substringBefore(' ')} or /help", color = skin.dim) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = skin.border, unfocusedBorderColor = skin.selection,
                    focusedTextColor = skin.text, unfocusedTextColor = skin.text, cursorColor = skin.accent,
                ),
                shape = RoundedCornerShape(14.dp),
            )
            if (vm.busy && vm.input.isBlank()) {
                IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "stop", tint = skin.error) }
            } else {
                IconButton(onClick = { vm.send() }) { Icon(Icons.AutoMirrored.Filled.Send, "send", tint = skin.accent) }
            }
        }
    }

    vm.approval?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("⚠️ Dangerous command") },
            text = {
                Column {
                    Text(req.reason, color = skin.warn)
                    Spacer(Modifier.size(8.dp))
                    Text(req.command, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.background(skin.surface, RoundedCornerShape(6.dp)).padding(8.dp))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.answerApproval(true, always = true) }) { Text("Always (session)") }
                    TextButton(onClick = { vm.answerApproval(true) }) { Text("Allow once") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerApproval(false) }) { Text("Deny", color = skin.error) } },
        )
    }

    vm.clarify?.let { req ->
        var answer by remember(req) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("❓ ${req.question}") },
            text = {
                Column {
                    req.choices.forEach { c ->
                        Text("• $c", color = skin.accent, modifier = Modifier.fillMaxWidth().clickable { vm.answerClarify(c) }.padding(vertical = 8.dp))
                    }
                    OutlinedTextField(answer, { answer = it }, placeholder = { Text("Your answer") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.answerClarify(answer.ifBlank { "(no answer)" }) }) { Text("Answer") } },
        )
    }
}

@Composable
fun Banner() {
    val skin = LocalSkin.current
    val palette = listOf(skin.border, skin.border, skin.accent, skin.accent, skin.title, skin.title, skin.accent, skin.accent,
        skin.border, skin.border, skin.dim, skin.dim, skin.dim, skin.dim)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ThinkingOrbs(OrbMode.Idle, 96.dp)
        Skins.CADUCEUS.forEachIndexed { i, line ->
            Text(line, color = palette.getOrElse(i) { skin.dim }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 12.sp)
        }
        Spacer(Modifier.size(14.dp))
        Text("${skin.symbol} ${skin.agentName}", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("Welcome to ${skin.agentName}! Type your message or /help for commands.", color = skin.text.copy(alpha = 0.8f),
            fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
        Spacer(Modifier.size(10.dp))
        listOf("💻 Sandbox terminal · Alpine Linux", "🧠 Persistent memory & skills", "🤖 Bot mode: a roster of agents · 📡 Telegram · Discord · Slack", "⏰ Cron scheduler · 🔀 subagents")
            .forEach { Text(it, color = skin.label, fontSize = 12.sp) }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val skin = LocalSkin.current
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.background(skin.selection, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 320.dp)
            ) {
                item.images.forEach { DataImage(it) }
                if (item.text.isNotBlank()) Text(item.text, color = skin.text, fontSize = 15.sp)
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (item.reasoning.isNotBlank()) {
                var open by remember { mutableStateOf(false) }
                Text(
                    (if (open) "▾ " else "▸ ") + "💭 reasoning" + if (item.streaming && item.text.isEmpty()) "…" else "",
                    color = skin.dim, fontSize = 12.sp, modifier = Modifier.clickable { open = !open }.padding(bottom = 2.dp),
                )
                if (open) Text(item.reasoning, color = skin.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            if (item.text.isNotBlank() || item.streaming) {
                Text(" ${skin.symbol} ${skin.agentName.substringBefore(' ')} ", color = skin.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(skin.responseBorder, RoundedCornerShape(4.dp)).padding(horizontal = 2.dp))
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, skin.responseBorder.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp).animateContentSize()) {
                    MarkdownText(item.text + if (item.streaming) " ▌" else "")
                }
            }
        }
        is ChatItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${skin.toolPrefix} ${item.emoji} ", color = skin.border, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(item.name, color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("  " + item.preview, color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(
                        item.durationMs?.let { "  ${"%.1f".format(it / 1000.0)}s" } ?: "  …",
                        color = if (item.result?.contains("\"error\"") == true) skin.error else skin.ok, fontSize = 11.sp,
                    )
                }
                if (open) {
                    Text("args: " + item.args.take(2000), color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    item.result?.let {
                        Text(it.take(6000), color = skin.text.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp).background(skin.surface, RoundedCornerShape(6.dp)).padding(8.dp))
                    }
                }
            }
        }
        is ChatItem.Info -> Text(item.text, color = if (item.isError) skin.error else skin.label, fontSize = 13.5.sp,
            fontFamily = if (item.text.lines().size > 2) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(skin.surface.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(10.dp))
        is ChatItem.Media -> {
            val rt = com.ersaz.hermesmobile.HermesRuntime.get(LocalContext.current)
            Text("🔊 ${java.io.File(item.path).name}  ▶ play", color = skin.accent,
                modifier = Modifier.clickable { rt.speech.play(java.io.File(item.path)) }.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun DataImage(uri: String) {
    val bmp = remember(uri) {
        runCatching {
            val b64 = uri.substringAfter("base64,", "")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(200.dp).padding(bottom = 6.dp), contentScale = ContentScale.Crop)
    else Text("🖼️ image", color = Color.Gray)
}

@Composable
private fun StatusBar(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val pct = vm.contextPct
    val color = when { pct >= 85 -> skin.error; pct >= 60 -> skin.warn; else -> skin.ok }
    Row(Modifier.fillMaxWidth().background(skin.surface).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⚕ ${vm.agent.model().substringAfterLast('/')}", color = skin.title, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("ctx $pct%", color = color, fontSize = 11.sp)
        Text("  │  ${vm.sessionTitle.take(24)}", color = skin.dim, fontSize = 11.sp, maxLines = 1)
    }
}

/** Downscale a picked image and encode it as a data: URI for vision models. */
fun imageDataUri(context: android.content.Context, uri: Uri): String? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return null
    val scale = minOf(1f, 1568f / maxOf(bmp.width, bmp.height))
    val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()
