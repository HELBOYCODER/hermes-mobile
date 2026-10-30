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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import android.graphics.Bitmap
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
import androidx.compose.ui.text.style.TextDirection
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
fun ChatScreen(vm: ChatViewModel, onOpenBrowser: () -> Unit = {}, onOpenTerminal: () -> Unit = {}) {
    // 2.7.2: NO chat-only skin. The chat page uses the ambient theme from
    // MainActivity — the user's chosen skin — exactly like every other screen.
    // The old atelier wrapper made the chat page a foreign palette pasted into
    // the app (light cards on dark skins and vice versa); it is gone.
    ChatScreenBody(vm, onOpenBrowser, onOpenTerminal)
}

@Composable
private fun ChatScreenBody(vm: ChatViewModel, onOpenBrowser: () -> Unit = {}, onOpenTerminal: () -> Unit = {}) {
    val skin = LocalSkin.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Follow the conversation, but don't yank the reader back down on every streamed token after they
    // scrolled up to read something. A new item (their own message, a tool call) still jumps to the end.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // "At the bottom" = the final item's end is (nearly) on screen; slack absorbs the newest delta.
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 400)
        }
    }
    var lastCount by remember { mutableStateOf(0) }
    LaunchedEffect(vm.items.size, (vm.items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (vm.items.isEmpty()) { lastCount = 0; return@LaunchedEffect }
        val grew = vm.items.size != lastCount
        lastCount = vm.items.size
        val end = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        if (grew) listState.animateScrollToItem(end)
        else if (atBottom) listState.scrollToItem(end, Int.MAX_VALUE / 2)
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
        // Header first, before the transcript: model pill, thinking pill, cost chip, context bar.
        var showModels by remember { mutableStateOf(false) }
        ChatHeader(vm) { showModels = true }
        if (showModels) ModelPickerSheet(vm) { showModels = false }
        LazyColumn(
            Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (vm.items.isEmpty()) item { Banner(vm) }
            // Folded transcript, computed inline each composition so a tool call that just
            // finished (result null -> value) re-groups immediately; running calls never fold.
            val display = foldTranscript(vm.items)
            items(display, key = { it.key }) { row ->
                when (row) {
                    is SingleRow -> ChatRow(row.item, vm)
                    is ToolGroupData -> ToolGroupRow(row.tools, vm)
                }
            }
            if (vm.busy) item { Spinner() }
        }

        BrowserMiniPreview(vm.runtime, onOpenBrowser)
        SandboxMiniPreview(vm, onOpenTerminal)
        SubAgentCards(vm)

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
                                Text(cmd.trim(), color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                                Text(desc, color = skin.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (vm.queued.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.queued.size} queued", color = skin.label, fontSize = 11.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.queued.clear() }) { Icon(Icons.Default.Close, "clear queue", tint = skin.dim) }
            }
        }
        SuggestedRepliesRow(vm)
        HarnessControls(vm)
        if (vm.clarify != null) ClarifyCard(vm)
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
                    TextButton(onClick = { vm.answerApproval(true, always = true) }) { Text("Always (session)", fontSize = 13.sp) }
                    TextButton(onClick = { vm.answerApproval(true) }) { Text("Allow once", fontSize = 13.sp) }
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerApproval(false) }) { Text("Deny", color = skin.error, fontSize = 13.sp) } },
        )
    }

}

/**
 * Open Minis-inspired in-chat activity monitor: read-only visual preview of Hermes' own tab.
 * Take Over deliberately moves the existing WebView to a dedicated gesture-safe screen.
 * Signing in is done by the human; no session cookies or credentials enter chat/model context.
 */
@Composable
private fun BrowserMiniPreview(rt: com.ersaz.hermesmobile.HermesRuntime, onOpenBrowser: () -> Unit) {
    val skin = LocalSkin.current
    val browser = rt.browser
    val handover by browser.handover.collectAsState()
    val requests by browser.showRequests.collectAsState()
    var frameNumber by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(handover) {
        while (handover != null) {
            delay(2700)
            frameNumber++
        }
    }
    val tab = remember(frameNumber, requests, handover) { browser.active }
    LaunchedEffect(tab?.id, frameNumber, handover) {
        if (tab != null && tab.url != "about:blank") {
            preview = runCatching { browser.thumbnail(tab) }.getOrNull()
        } else preview = null
    }
    if (tab != null && tab.url != "about:blank" && (handover != null || rt.browser.active?.loading == true)) {
        Surface(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = skin.surface,
            shadowElevation = 2.dp,
        ) {
            Row(Modifier.clickable(onClick = onOpenBrowser).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                preview?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview of the active browser tab",
                        modifier = Modifier.size(width = 120.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (handover == null) "Browser activity" else "Verification needs you",
                        color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(tab.title.ifBlank { tab.url }.take(85), color = skin.dim, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (handover == null) "Tap to expand" else "Take Over · complete sign-in or CAPTCHA",
                        color = skin.accent, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Compact status of real PRoot processes and terminal sessions, never a simulated run. */
@Composable
private fun HarnessControls(vm: ChatViewModel) {
    val skin = LocalSkin.current
    val rt = vm.runtime
    val s = rt.settings
    // Persist a progress checkpoint after each completed assistant turn.
    androidx.compose.runtime.LaunchedEffect(vm.items.size, vm.busy) {
        if (vm.busy) return@LaunchedEffect
        val last = vm.items.lastOrNull() as? ChatItem.Assistant ?: return@LaunchedEffect
        if (last.text.isBlank()) return@LaunchedEffect
        val lastUser = vm.items.filterIsInstance<ChatItem.User>().lastOrNull()?.text.orEmpty()
        com.ersaz.hermesmobile.agent.RunCheckpoint.save(rt, vm.sessionTitle.ifBlank { "chat" }, vm.items.size, lastUser, last.text)
    }
    // 2.7.2 declutter: the persistent "Run: …" line and its Run info details no
    // longer live on the chat page. The same run info is shown in the model
    // picker sheet (model pill tap) instead; nothing about the run itself changed.
    Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 12.dp)) {
        // Task-based routing suggestion (user override wins).
        if (s.taskRouting && !vm.busy && vm.input.isNotBlank()) {
            val store = rt.modelStore
            val suggestion = androidx.compose.runtime.remember(vm.input) {
                com.ersaz.hermesmobile.agent.TaskRouter.suggest(vm.input, store.all())
            }
            if (suggestion != null && !store.isActive(suggestion)) {
                FilterChip(selected = false, onClick = { store.select(suggestion); vm.modelChanged() },
                    label = { Text("Suggested model: ${suggestion.model} — use it") })
            }
        }
        // Team mode toggle (ON by default): chip only. The description paragraph
        // used to sit inline here and crowded the chat; it now lives on the
        // Settings → Agent engine toggle instead.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = s.teamMode, onClick = { s.teamMode = !s.teamMode },
                label = { Text("Team mode", fontSize = 13.sp) })
        }
        // Morning brief: nightly-learning proposals with Approve/Reject
        // buttons per rule. Nothing here is auto-adopted.
        var rulesRev by androidx.compose.runtime.remember { mutableStateOf(0) }
        @Suppress("UNUSED_VARIABLE") val rulesTick = rulesRev
        val rulesStore = androidx.compose.runtime.remember(rulesRev) { com.ersaz.hermesmobile.agent.RulesStore(rt) }
        val proposals = androidx.compose.runtime.remember(rulesRev) { rulesStore.proposals() }
        if (proposals.isNotEmpty()) {
            Text("Morning brief — proposed rules (approve to activate, reject to discard forever):", color = skin.title, fontSize = 13.sp)
            proposals.forEachIndexed { i, p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${i + 1}. ${p.rule}", color = skin.text, fontSize = 13.sp)
                    Text("Why: ${p.reason}", color = skin.dim, fontSize = 11.sp)
                    Row {
                        TextButton(onClick = { rulesStore.approve(i + 1); rulesRev++ }) { Text("Approve", fontSize = 13.sp) }
                        TextButton(onClick = { rulesStore.reject(i + 1); rulesRev++ }) { Text("Reject", fontSize = 13.sp) }
                    }
                }
            }
        }
        // Resume, only for a fresh session with a saved checkpoint.
        if (vm.items.isEmpty()) {
            val checkpoint = androidx.compose.runtime.remember { com.ersaz.hermesmobile.agent.RunCheckpoint.load(rt) }
            if (checkpoint != null) {
                TextButton(onClick = { vm.input = com.ersaz.hermesmobile.agent.RunCheckpoint.resumePrompt(checkpoint) }) {
                    Text("Resume interrupted run (${checkpoint.turns} turns, summary-based — does not replay tool calls)", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: ChatViewModel, onImage: () -> Unit, onFile: () -> Unit, onMic: () -> Unit) {
    val skin = LocalSkin.current
    var menu by remember { mutableStateOf(false) }
    val hasAttachments = vm.pendingImages.isNotEmpty() || vm.pendingFiles.isNotEmpty()
    val canSend = vm.input.isNotBlank() || hasAttachments || vm.listening

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
                // 2.7.2 declutter: the "Summarize the file I attach" chip lives
                // ONLY here, once a file is actually attached — never on the
                // empty new session. Tapping it fills the composer, never sends.
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp)) {
                    FilterChip(selected = false, onClick = { vm.input = "Summarize the file I attach" },
                        label = { Text("Summarize the file I attach", fontSize = 13.sp) })
                }
            }
            // 2.7.2 declutter: the Spec-first chip and "Engine:" label are gone
            // from the composer row. Behaviour is unchanged — Spec-first stays
            // ON by default (settings.specFirst, toggled in Settings → Agent
            // engine) and the engine is chosen in the model picker sheet.
            BasicTextField(
                value = vm.input,
                onValueChange = { if (vm.listening || vm.transcribing) vm.pauseVoice(); vm.input = it },
                textStyle = TextStyle(color = skin.text, fontSize = 16.sp, lineHeight = 22.sp, textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start),
                cursorBrush = SolidColor(skin.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 180.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (vm.input.isEmpty()) {
                            Text(if (vm.listening) "Listening… tap pause to keep text, ↑ to send" else if (vm.transcribing) "Processing voice…" else "Message ${skin.agentName.substringBefore(' ')}, or type /help",
                                color = skin.dim, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
            if (vm.listening || vm.voicePaused) {
                Text(
                    if (vm.listening) "Recording · Pause keeps your text" else "Paused · Tap mic to continue, or send",
                    color = if (vm.listening) skin.error else skin.label, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            vm.voiceError?.let {
                Text(it, color = skin.error, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                if (vm.listening || vm.transcribing) {
                    IconButton(onClick = { vm.cancelVoice() }) { Icon(Icons.Default.Close, "cancel recording", tint = skin.dim) }
                }
                MicButton(vm.listening, vm.transcribing, onMic)
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
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(skin.selection)
            .clickable(enabled = !vm.listening) { vm.cycleVoiceLang() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MicButton(listening: Boolean, transcribing: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val pulse = rememberInfiniteTransition(label = "mic")
    val a by pulse.animateFloat(1f, 0.45f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "micAlpha")
    if (transcribing) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = skin.accent, strokeWidth = 2.dp)
        }
    } else if (listening) {
        // Token-only: no hardcoded white-on-red. The pulsing pause icon is the
        // skin's own error token on the composer surface.
        Box(
            Modifier.size(48.dp).alpha(a).clip(CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Pause, "pause dictation and keep text", tint = skin.error, modifier = Modifier.size(20.dp)) }
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
    val skin = LocalSkin.current
    val bmp = remember(uri) { decodeDataImage(uri) }
    Box {
        if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(skin.selection))
        // Token-only scrim: the skin's own background at 60% with the skin's
        // title token on top — no hardcoded black/white.
        Icon(Icons.Default.Close, "remove", tint = skin.title,
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(skin.bg.copy(alpha = 0.6f)).clickable(onClick = onRemove).padding(2.dp))
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
        Text("How can I help?", color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("Type, tap the mic to dictate in Persian or English, or attach any file.",
            color = skin.dim, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
        // 2.7.2 declutter: no suggestion chips on the empty new session. The
        // file-summary chip reappears contextually in the composer once a file
        // is actually attached; the other starters live in Prompt school.
    }
}

@Composable
private fun ChatRow(item: ChatItem, vm: ChatViewModel) {
    val skin = LocalSkin.current
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(skin.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                item.images.forEach { DataImage(it) }
                // No fillMaxWidth: it stretched every bubble (even "hi") to the 320dp cap.
                if (item.text.isNotBlank()) Text(item.text, color = skin.text, fontSize = 16.sp, lineHeight = 22.sp,
                    style = TextStyle(textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start))
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                item.failure?.let { failure ->
                    Text(failure, color = skin.error, fontSize = 11.sp,
                        style = TextStyle(textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start), modifier = Modifier.padding(top = 8.dp))
                }
                Row {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }) { Text("Copy", fontSize = 13.sp) }
                    if (item.failure != null) TextButton(enabled = vm.canRetry(item), onClick = { vm.retryFailed(item) }) { Text("Retry", fontSize = 13.sp) }
                }
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (item.reasoning.isNotBlank()) {
                var open by remember { mutableStateOf(false) }
                Text(
                    (if (open) "▾ " else "▸ ") + "Thinking" + if (item.streaming && item.text.isEmpty()) "…" else "",
                    color = skin.dim, fontSize = 13.sp, modifier = Modifier.clickable { open = !open }.padding(bottom = 4.dp),
                )
                if (open) Text(item.reasoning, color = skin.dim, fontSize = 11.sp, lineHeight = 16.sp,
                    style = TextStyle(textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start),
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            }
            if (item.text.isNotBlank() || item.streaming) {
                Box(Modifier.fillMaxWidth().animateContentSize()) {
                    MarkdownText(item.text + if (item.streaming) " ▌" else "")
                }
                if (!item.streaming && item.text.length > 1) {
                    val rt = com.ersaz.hermesmobile.HermesRuntime.get(LocalContext.current)
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    Row {
                        Text("🔊 read", color = skin.dim, fontSize = 11.sp, modifier = Modifier.clickable { rt.speech.speak(com.ersaz.hermesmobile.ui.plainSpeech(item.text)) }.padding(end = 14.dp, top = 4.dp))
                        Text("⧉ Copy", color = skin.dim, fontSize = 11.sp, modifier = Modifier.clickable { clip.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }.padding(top = 4.dp))
                    }
                }
            }
        }
        is ChatItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.result == null) Text("● ", color = skin.ok, fontSize = 11.sp)
                    Text("${item.emoji}  ", fontSize = 13.sp)
                    Text(item.name, color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("  " + item.preview, color = skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
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
        is ChatItem.Info -> Text(item.text, style = TextStyle(textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start), color = if (item.isError) skin.error else skin.label, fontSize = 13.sp,
            fontFamily = if (item.text.lines().size > 2) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(skin.surface).padding(12.dp))
        is ChatItem.FileOutput -> GeneratedFileCard(item.path)
        is ChatItem.Media -> {
            GeneratedFileCard(item.path)
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
    else Text("🖼️ image", color = LocalSkin.current.dim)
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

/** Transcript rows after folding: a single item, or a run of consecutive finished tool calls. */
private sealed interface TranscriptRow { val key: String }
private data class SingleRow(val item: ChatItem) : TranscriptRow { override val key get() = item.key }
private data class ToolGroupData(val tools: List<ChatItem.Tool>) : TranscriptRow { override val key get() = "tools:" + tools.first().key }

/**
 * Fold consecutive FINISHED tool calls into one "Ran N tools" row (on by default). A running
 * tool call (result == null) is never folded — it stays visible with its working state — and
 * folding never hides a tool's content: tapping the row expands the individual calls.
 */
private fun foldTranscript(items: List<ChatItem>): List<TranscriptRow> {
    val out = ArrayList<TranscriptRow>()
    var pending = ArrayList<ChatItem.Tool>()
    fun flush() { if (pending.isNotEmpty()) { out += ToolGroupData(pending); pending = ArrayList() } }
    for (item in items) {
        if (item is ChatItem.Tool && item.result != null) pending += item
        else { flush(); out += SingleRow(item) }
    }
    flush()
    return out
}

@Composable
private fun ToolGroupRow(tools: List<ChatItem.Tool>, vm: ChatViewModel) {
    val skin = LocalSkin.current
    var open by remember(tools.first().key) { mutableStateOf(false) }
    val totalSec = tools.mapNotNull { it.durationMs }.sum() / 1000.0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 3.dp)
        .then(if (vm.runtime.settings.chatAnimations) Modifier.animateContentSize() else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▾ " else "▸ ", color = skin.dim, fontSize = 13.sp)
            Text("Ran ${tools.size} tools", color = skin.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("  ·  ${"%.1f".format(totalSec)}s total", color = skin.dim, fontSize = 11.sp,
                modifier = Modifier.weight(1f, fill = false))
            Text(if (open) "hide" else "show", color = skin.accent, fontSize = 11.sp)
        }
        if (open) tools.forEach { ChatRow(it, vm) }
    }
}

/**
 * In-chat question card: when the assistant asks (options, confirmation, or a free-text
 * question), the card appears inline above the composer and the turn waits on it — no
 * blocking dialog, no lost scroll position. Tapping a choice answers immediately; the text
 * field answers free-form.
 */
@Composable
private fun ClarifyCard(vm: ChatViewModel) {
    val req = vm.clarify ?: return
    val skin = LocalSkin.current
    var answer by remember(req) { mutableStateOf("") }
    Surface(
        Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = skin.surface, shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(req.question, color = skin.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (req.choices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    req.choices.forEach { c ->
                        Text(c, color = skin.accent, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { vm.answerClarify(c) }.padding(vertical = 6.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = answer, onValueChange = { answer = it },
                    placeholder = { Text("Your answer", fontSize = 13.sp) }, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.answerClarify(answer.ifBlank { "(no answer)" }) }) { Text("Answer") }
            }
        }
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

/** Strip markdown noise so text-to-speech doesn't read symbols aloud. */
fun plainSpeech(md: String): String = md
    .replace(Regex("```[\\s\\S]*?```"), " code block. ")
    .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("[*_`#>|~]+"), " ")
    .replace(Regex("\\s+"), " ").trim()
