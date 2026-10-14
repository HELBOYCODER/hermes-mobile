package com.ersaz.hermesmobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ersaz.hermesmobile.commands.Commands
import com.ersaz.hermesmobile.stt.VoiceLang
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val ContentMaxWidth = 760.dp

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.items.size, (vm.items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.size)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { imageDataUri(context, uri) }
            if (data != null) vm.pendingImages += data
        }
    }
    // Any file type: PDFs, office docs, archives, audio, code, … copied into /root/uploads for the agent.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    importAttachment(context, u)?.let { att -> Triple(att, if (att.isImage) imageDataUri(context, u) else null, u) }
                }
            }
            imported.forEach { (att, img, _) ->
                vm.pendingFiles += att
                if (img != null) vm.pendingImages += img
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleVoice() else vm.voiceError = "Microphone permission is needed for voice typing."
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(
            Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (vm.items.isEmpty()) item { Banner(vm) }
            items(vm.items, key = { it.key }) { item -> ChatRow(item) }
            if (vm.busy) item { Spinner() }
        }

        if (vm.runtime.settings.showStatusBar) StatusLine(vm)

        // Slash-command autocomplete (Hermes CLI completion menu)
        if (vm.input.startsWith("/") && !vm.input.contains(' ')) {
            val sugg = remember(vm.input) { Commands.suggestions(vm.runtime, vm.input) }
            if (sugg.isNotEmpty()) {
                Surface(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp), color = skin.surface, shadowElevation = 3.dp,
                ) {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(sugg) { (cmd, desc) ->
                            Row(Modifier.fillMaxWidth().clickable { vm.input = cmd }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Text(cmd.trim(), color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(130.dp))
                                Text(desc, color = skin.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        Composer(vm, onImage = { imagePicker.launch("image/*") }, onFile = { filePicker.launch(arrayOf("*/*")) }, onMic = onMic)
    }

    vm.approval?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Dangerous command") },
            text = {
                Column {
                    Text(req.reason, color = skin.warn)
                    Spacer(Modifier.size(8.dp))
                    Text(req.command, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.background(skin.surface, RoundedCornerShape(8.dp)).padding(8.dp))
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
            title = { Text(req.question) },
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

// ------------------------------------------------------------------ composer

/** One floating pill, no outline: [attachments] / text / [+ · language · … · mic · send]. */
@Composable
private fun Composer(vm: ChatViewModel, onImage: () -> Unit, onFile: () -> Unit, onMic: () -> Unit) {
    val skin = LocalSkin.current
    var menu by remember { mutableStateOf(false) }
    val hasAttachments = vm.pendingImages.isNotEmpty() || vm.pendingFiles.isNotEmpty()
    val canSend = vm.input.isNotBlank() || hasAttachments

    Surface(
        Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        shape = RoundedCornerShape(28.dp), color = skin.surface, shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (hasAttachments) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.pendingImages.forEachIndexed { i, uri -> ImageChip(uri) { vm.pendingImages.removeAt(i) } }
                    vm.pendingFiles.forEachIndexed { i, f ->
                        FileChip(f) { vm.pendingFiles.removeAt(i) }
                    }
                }
            }
            BasicTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                textStyle = TextStyle(color = skin.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(skin.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 180.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (vm.input.isEmpty()) {
                            Text(if (vm.listening) "Listening…" else "Message ${skin.agentName.substringBefore(' ')}, or type /help",
                                color = skin.dim, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
            vm.voiceError?.let {
                Text(it, color = skin.error, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { vm.voiceError = null }.padding(horizontal = 14.dp, vertical = 2.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, "attach", tint = skin.label) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { menu = false; onImage() })
                        DropdownMenuItem(text = { Text("File — any type") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { menu = false; onFile() })
                    }
                }
                LangChip(vm)
                Spacer(Modifier.weight(1f))
                MicButton(vm.listening, onMic)
                Spacer(Modifier.width(2.dp))
                if (vm.busy && !canSend) {
                    RoundButton(skin.selection, skin.error, Icons.Default.Stop, "stop") { vm.stop() }
                } else {
                    RoundButton(if (canSend) skin.accent else skin.selection, if (canSend) skin.bg else skin.dim,
                        Icons.Default.ArrowUpward, "send", enabled = canSend) { vm.send() }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun LangChip(vm: ChatViewModel) {
    val skin = LocalSkin.current
    Text(
        vm.voiceLang.short,
        color = if (vm.listening) skin.dim else skin.label,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(skin.selection)
            .clickable(enabled = !vm.listening) { vm.cycleVoiceLang() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val pulse = rememberInfiniteTransition(label = "mic")
    val a by pulse.animateFloat(1f, 0.45f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "micAlpha")
    if (listening) {
        Box(
            Modifier.size(40.dp).alpha(a).clip(CircleShape).background(skin.error).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Stop, "stop dictation", tint = Color.White, modifier = Modifier.size(20.dp)) }
    } else {
        IconButton(onClick = onClick) { Icon(Icons.Default.Mic, "voice typing", tint = skin.label) }
    }
}

@Composable
private fun RoundButton(
    fill: Color, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(fill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ImageChip(uri: String, onRemove: () -> Unit) {
    val bmp = remember(uri) { decodeDataImage(uri) }
    Box {
        if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(LocalSkin.current.selection))
        Icon(Icons.Default.Close, "remove", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onRemove).padding(2.dp))
    }
}

@Composable
private fun FileChip(f: Attachment, onRemove: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        Modifier.height56().clip(RoundedCornerShape(12.dp)).background(skin.selection).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, null, tint = skin.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(horizontal = 8.dp).widthIn(max = 150.dp)) {
            Text(f.name, color = skin.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(f.size), color = skin.dim, fontSize = 11.sp)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "remove", tint = skin.dim, modifier = Modifier.size(16.dp))
        }
    }
}

private fun Modifier.height56() = this.heightIn(min = 56.dp, max = 56.dp)

// ------------------------------------------------------------------ transcript

@Composable
fun Banner(vm: ChatViewModel) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(skin.symbol, color = skin.accent, fontSize = 48.sp)
        Spacer(Modifier.size(10.dp))
        Text("How can I help?", color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text("Type, tap the mic to dictate in Persian or English, or attach any file.",
            color = skin.dim, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
        Spacer(Modifier.size(22.dp))
        listOf(
            "Summarize the file I attach",
            "Connect to my GitHub and list my repos",
            "Write a Python script for this and run it",
            "What can you do on this phone?",
        ).forEach { prompt ->
            Text(prompt, color = skin.text.copy(alpha = 0.9f), fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(skin.surface)
                    .clickable { vm.input = prompt }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val skin = LocalSkin.current
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(skin.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                item.images.forEach { DataImage(it) }
                if (item.text.isNotBlank()) Text(item.text, color = skin.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (item.reasoning.isNotBlank()) {
                var open by remember { mutableStateOf(false) }
                Text(
                    (if (open) "▾ " else "▸ ") + "Thinking" + if (item.streaming && item.text.isEmpty()) "…" else "",
                    color = skin.dim, fontSize = 12.sp, modifier = Modifier.clickable { open = !open }.padding(bottom = 4.dp),
                )
                if (open) Text(item.reasoning, color = skin.dim, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            }
            if (item.text.isNotBlank() || item.streaming) {
                Box(Modifier.fillMaxWidth().animateContentSize()) {
                    MarkdownText(item.text + if (item.streaming) " ▌" else "")
                }
            }
        }
        is ChatItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.emoji}  ", fontSize = 12.sp)
                    Text(item.name, color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    Text("  " + item.preview, color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(
                        item.durationMs?.let { "  ${"%.1f".format(it / 1000.0)}s" } ?: "  …",
                        color = if (item.result?.contains("\"error\"") == true) skin.error else skin.dim, fontSize = 11.sp,
                    )
                }
                if (open) {
                    Text("args: " + item.args.take(2000), color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    item.result?.let {
                        Text(it.take(6000), color = skin.text.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(skin.surface).padding(10.dp))
                    }
                }
            }
        }
        is ChatItem.Info -> Text(item.text, color = if (item.isError) skin.error else skin.label, fontSize = 13.sp,
            fontFamily = if (item.text.lines().size > 2) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(skin.surface).padding(12.dp))
        is ChatItem.Media -> {
            val rt = com.ersaz.hermesmobile.HermesRuntime.get(LocalContext.current)
            Text("🔊 ${java.io.File(item.path).name}  ▶ play", color = skin.accent,
                modifier = Modifier.clickable { rt.speech.play(java.io.File(item.path)) }.padding(vertical = 6.dp))
        }
    }
}

private fun decodeDataImage(uri: String) = runCatching {
    val b64 = uri.substringAfter("base64,", "")
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun DataImage(uri: String) {
    val bmp = remember(uri) { decodeDataImage(uri) }
    if (bmp != null) Image(bmp, null, Modifier.size(200.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    else Text("🖼️ image", color = Color.Gray)
}

@Composable
private fun Spinner() {
    val skin = LocalSkin.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(700); tick++ } }
    val verb = skin.thinkingVerbs[(tick / 3) % skin.thinkingVerbs.size]
    val dots = ".".repeat(tick % 3 + 1)
    Text("${skin.symbol} $verb$dots", color = skin.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun StatusLine(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val pct = vm.contextPct
    val color = when { pct >= 85 -> skin.error; pct >= 60 -> skin.warn; else -> skin.dim }
    Text(
        "${vm.agent.model().substringAfterLast('/')}  ·  ctx $pct%",
        color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
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
