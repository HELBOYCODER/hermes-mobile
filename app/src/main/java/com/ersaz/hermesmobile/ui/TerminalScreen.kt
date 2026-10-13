package com.ersaz.hermesmobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.sandbox.Sandbox
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/** Process-wide terminal tabs so shells survive navigation. */
object TerminalTabs {
    val sessions = mutableStateListOf<TerminalSession>()
    var current by mutableIntStateOf(0)
    var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var fontSize = 30

    val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { if (sessions.getOrNull(current) === changedSession) view?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val i = sessions.indexOf(finishedSession)
            if (i >= 0) { sessions.removeAt(i); current = current.coerceAtMost((sessions.size - 1).coerceAtLeast(0)) }
            sessions.getOrNull(current)?.let { view?.attachSession(it) }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            view?.context?.let { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text)) }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val c = view?.context ?: return
            val text = (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() ?: return
            session?.emulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    fun newSession(rt: HermesRuntime): TerminalSession {
        val sb: Sandbox = rt.sandbox
        val n = sessions.size + 1
        val argv = sb.prootArgv(
            listOf("/bin/sh", "-c", "cat /etc/motd 2>/dev/null; exec /bin/sh -l"),
            "/root",
            mapOf("HERMES_SESSION" to "terminal$n", "COLORTERM" to "truecolor"),
        )
        val env = sb.hostEnv().map { "${it.key}=${it.value}" } + listOf("HOME=${rt.app.filesDir}", "TERM=xterm-256color")
        val s = TerminalSession(sb.prootBin.absolutePath, rt.app.filesDir.absolutePath, argv.toTypedArray(), env.toTypedArray(), 5000, client)
        sessions += s
        current = sessions.lastIndex
        return s
    }
}

@Composable
fun TerminalScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val state by rt.sandbox.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    when (val st = state) {
        is Sandbox.State.Ready -> {}
        is Sandbox.State.Installing -> {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                CircularProgressIndicator(color = skin.accent)
                Text("Setting up Alpine Linux sandbox…\n${st.progress}", color = skin.text, modifier = Modifier.padding(16.dp))
            }
            return
        }
        else -> {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text("☤ Sandbox terminal", color = skin.title, fontSize = 20.sp)
                Text(
                    (st as? Sandbox.State.Failed)?.error ?: "Alpine Linux runs inside the app through PRoot — no Termux or root needed.",
                    color = if (st is Sandbox.State.Failed) skin.error else skin.text, modifier = Modifier.padding(16.dp),
                )
                Button(onClick = { scope.launch { rt.sandbox.ensureInstalled() } }) { Text("Install / repair sandbox") }
            }
            LaunchedEffect(Unit) { if (st is Sandbox.State.NotInstalled) rt.sandbox.ensureInstalled() }
            return
        }
    }

    LaunchedEffect(Unit) { if (TerminalTabs.sessions.isEmpty()) TerminalTabs.newSession(rt) }

    Column(Modifier.fillMaxSize().imePadding().background(androidx.compose.ui.graphics.Color.Black)) {
        // Tabs
        Row(Modifier.fillMaxWidth().background(skin.surface).horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TerminalTabs.sessions.forEachIndexed { i, _ ->
                val sel = i == TerminalTabs.current
                Text(" sh ${i + 1} ", color = if (sel) skin.bg else skin.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.padding(2.dp).background(if (sel) skin.accent else skin.selection, RoundedCornerShape(6.dp))
                        .clickable { TerminalTabs.current = i; TerminalTabs.view?.attachSession(TerminalTabs.sessions[i]) }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Text(" ＋ ", color = skin.accent, fontSize = 16.sp, modifier = Modifier.clickable {
                val s = TerminalTabs.newSession(rt); TerminalTabs.view?.attachSession(s)
            }.padding(horizontal = 10.dp))
            Text(" hermes ", color = skin.title, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.clickable {
                TerminalTabs.sessions.getOrNull(TerminalTabs.current)?.write("hermes\n")
            }.padding(horizontal = 8.dp))
            Text(" A− ", color = skin.label, modifier = Modifier.clickable { TerminalTabs.fontSize = (TerminalTabs.fontSize - 4).coerceAtLeast(14); TerminalTabs.view?.setTextSize(TerminalTabs.fontSize) }.padding(6.dp))
            Text(" A+ ", color = skin.label, modifier = Modifier.clickable { TerminalTabs.fontSize = (TerminalTabs.fontSize + 4).coerceAtMost(80); TerminalTabs.view?.setTextSize(TerminalTabs.fontSize) }.padding(6.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    TerminalView(c, null).apply {
                        setTextSize(TerminalTabs.fontSize)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTerminalViewClient(viewClient(this))
                        TerminalTabs.view = this
                        TerminalTabs.sessions.getOrNull(TerminalTabs.current)?.let { attachSession(it) }
                        requestFocus()
                    }
                },
                update = { v ->
                    TerminalTabs.view = v
                    TerminalTabs.sessions.getOrNull(TerminalTabs.current)?.let { s -> if (v.currentSession !== s) v.attachSession(s) }
                },
            )
        }
        ExtraKeys()
    }
}

@Composable
private fun ExtraKeys() {
    val skin = LocalSkin.current
    fun send(seq: String) { TerminalTabs.sessions.getOrNull(TerminalTabs.current)?.write(seq) }
    val keys = listOf(
        "ESC" to "\u001b", "TAB" to "\t", "CTRL" to "", "ALT" to "", "←" to "\u001b[D", "↑" to "\u001b[A",
        "↓" to "\u001b[B", "→" to "\u001b[C", "HOME" to "\u001b[H", "END" to "\u001b[F", "-" to "-", "/" to "/", "|" to "|",
        "~" to "~", "PGUP" to "\u001b[5~", "PGDN" to "\u001b[6~",
    )
    Row(Modifier.fillMaxWidth().background(skin.surface).horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        keys.forEach { (label, seq) ->
            val active = (label == "CTRL" && TerminalTabs.ctrl) || (label == "ALT" && TerminalTabs.alt)
            Text(label, color = if (active) skin.bg else skin.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
                    .background(if (active) skin.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable {
                        when (label) {
                            "CTRL" -> TerminalTabs.ctrl = !TerminalTabs.ctrl
                            "ALT" -> TerminalTabs.alt = !TerminalTabs.alt
                            else -> send(seq)
                        }
                    }.padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

private fun viewClient(view: TerminalView) = object : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            TerminalTabs.fontSize = (TerminalTabs.fontSize * scale).toInt().coerceIn(14, 80)
            view.setTextSize(TerminalTabs.fontSize)
            return 1f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = TerminalTabs.ctrl.also { if (it) { TerminalTabs.ctrl = false } }
    override fun readAltKey(): Boolean = TerminalTabs.alt.also { if (it) { TerminalTabs.alt = false } }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
