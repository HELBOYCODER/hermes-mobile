package com.ersaz.hermesmobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ersaz.hermesmobile.HermesRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** One browser tab: a WebView plus what we've learned about its loading state. */
class BrowserTab(val id: Int, val web: WebView) {
    @Volatile var loading = false
    @Volatile var lastError: String? = null
    @Volatile var title: String = ""
    @Volatile var url: String = "about:blank"
    var pageFinished: CompletableDeferred<Unit> = CompletableDeferred()
    val dialogs = mutableListOf<String>()
    val console = ArrayDeque<String>()
}

/**
 * Browser automation for the agent: a small pool of real WebViews with Chrome's engine. The agent drives them
 * with accessibility-style snapshots ([snapshot] numbers every interactive element, then [click]/[type] by ref);
 * the user can open the same tabs on screen to sign in or solve a CAPTCHA, and the session (cookies) is shared.
 */
class BrowserManager(private val rt: HermesRuntime) {
    private val main = Handler(Looper.getMainLooper())
    private val tabs = linkedMapOf<Int, BrowserTab>()
    private val ids = AtomicInteger(1)
    @Volatile var activeId: Int = 0
        private set
    private val lock = Mutex()

    /** Tiny, nearly invisible container in the activity window so background tabs keep rendering (needed for screenshots). */
    @Volatile private var host: android.widget.FrameLayout? = null

    fun attachHost(h: android.widget.FrameLayout) {
        host = h
        tabList.forEach { park(it) }
    }

    private fun park(t: BrowserTab) {
        val h = host ?: return
        if (t.web.parent === h) return
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        h.addView(t.web, android.widget.FrameLayout.LayoutParams(VIEW_W, VIEW_H))
    }

    val shotsDir = File(rt.hermesHome, "browser").apply { mkdirs() }

    val tabList: List<BrowserTab> get() = synchronized(tabs) { tabs.values.toList() }
    val active: BrowserTab? get() = synchronized(tabs) { tabs[activeId] }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createTab(): BrowserTab = withContext(Dispatchers.Main) {
        if (tabList.size >= MAX_TABS) throw IllegalStateException("Too many tabs open (max $MAX_TABS). Close one first.")
        val web = WebView(rt.app)
        val id = ids.getAndIncrement()
        val tab = BrowserTab(id, web)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true; mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false; allowContentAccess = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { tab.loading = true; tab.url = url; tab.lastError = null; if (tab.pageFinished.isCompleted) tab.pageFinished = CompletableDeferred() }
            override fun onPageFinished(v: WebView, url: String) { tab.loading = false; tab.url = url; tab.title = v.title.orEmpty(); tab.pageFinished.complete(Unit) }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) { tab.lastError = "${e.errorCode}: ${e.description}"; tab.pageFinished.complete(Unit) }
            }
            // Keep navigation in the page: http(s) only, never intent:/file:/content:.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = r.url.scheme !in setOf("http", "https", "about")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean { tab.dialogs += "alert: $message"; result.confirm(); return true }
            override fun onJsConfirm(v: WebView, url: String, message: String, result: JsResult): Boolean { tab.dialogs += "confirm: $message (accepted)"; result.confirm(); return true }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean { tab.console.addLast("${m.messageLevel()}: ${m.message()}".take(300)); while (tab.console.size > 30) tab.console.removeFirst(); return true }
            override fun onReceivedTitle(v: WebView, title: String) { tab.title = title }
        }
        // Lay out at a phone-sized viewport so screenshots and layout-dependent JS work without being on screen.
        web.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
        web.layout(0, 0, VIEW_W, VIEW_H)
        synchronized(tabs) { tabs[id] = tab }
        park(tab)
        if (activeId == 0) activeId = id
        tab
    }

    suspend fun tab(id: Int?): BrowserTab = lock.withLock {
        val existing = id?.let { i -> synchronized(tabs) { tabs[i] } } ?: active
        existing ?: createTab().also { activeId = it.id }
    }

    suspend fun newTab(url: String?): BrowserTab {
        val t = lock.withLock { createTab().also { activeId = it.id } }
        if (!url.isNullOrBlank()) navigate(t, url)
        return t
    }

    fun switchTo(id: Int): Boolean = synchronized(tabs) { if (id in tabs) { activeId = id; true } else false }

    suspend fun close(id: Int): Boolean = withContext(Dispatchers.Main) {
        val t = synchronized(tabs) { tabs.remove(id) } ?: return@withContext false
        (t.web.parent as? android.view.ViewGroup)?.removeView(t.web)
        t.web.destroy()
        synchronized(tabs) { if (activeId == id) activeId = tabs.keys.firstOrNull() ?: 0 }
        true
    }

    // ---------------------------------------------------------------- navigation

    fun checkUrl(url: String): String {
        val u = url.trim().let { if (it.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))) it else "https://$it" }
        val scheme = try { URI(u).scheme?.lowercase() } catch (_: Exception) { null }
        require(scheme == "http" || scheme == "https" || u == "about:blank") { "Only http(s) pages can be opened." }
        return u
    }

    suspend fun navigate(t: BrowserTab, url: String, timeoutMs: Long = 30_000): String {
        val u = checkUrl(url)
        withContext(Dispatchers.Main) { t.pageFinished = CompletableDeferred(); t.web.loadUrl(u) }
        settle(t, timeoutMs)
        return statusLine(t)
    }

    suspend fun back(t: BrowserTab): String { withContext(Dispatchers.Main) { if (t.web.canGoBack()) { t.pageFinished = CompletableDeferred(); t.web.goBack() } }; settle(t); return statusLine(t) }
    suspend fun forward(t: BrowserTab): String { withContext(Dispatchers.Main) { if (t.web.canGoForward()) { t.pageFinished = CompletableDeferred(); t.web.goForward() } }; settle(t); return statusLine(t) }
    suspend fun reload(t: BrowserTab): String { withContext(Dispatchers.Main) { t.pageFinished = CompletableDeferred(); t.web.reload() }; settle(t); return statusLine(t) }

    /** Wait for load completion, then a short quiet period so SPAs can render. */
    suspend fun settle(t: BrowserTab, timeoutMs: Long = 20_000) {
        withTimeoutOrNull(timeoutMs) { t.pageFinished.await() }
        delay(450)
        // Late navigations (redirects triggered by JS) restart loading.
        var guard = 0
        while (t.loading && guard++ < 40) delay(250)
    }

    fun statusLine(t: BrowserTab): String =
        "tab ${t.id}: ${t.title.ifBlank { "(untitled)" }} — ${t.url}" + (t.lastError?.let { "  ⚠ load error $it" } ?: "")

    // ---------------------------------------------------------------- JS

    suspend fun eval(t: BrowserTab, js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            t.web.evaluateJavascript(js) { cont.resume(it ?: "null") }
        }
    }

    /** Evaluate and decode the JSON-string result of an expression. */
    suspend fun evalJson(t: BrowserTab, js: String): String {
        val raw = eval(t, js)
        if (raw == "null") return ""
        return try { JSONArray("[$raw]").get(0).toString() } catch (_: Exception) { raw }
    }

    suspend fun snapshot(t: BrowserTab, maxChars: Int = 9000): String {
        val r = evalJson(t, SNAPSHOT_JS)
        return statusLine(t) + (if (t.dialogs.isNotEmpty()) "\ndialogs: ${t.dialogs.takeLast(3)}" else "") + "\n" + r.take(maxChars)
    }

    suspend fun click(t: BrowserTab, ref: Int): String {
        val r = evalJson(t, "(function(){var e=document.querySelector('[data-hx=\"$ref\"]');if(!e)return 'no element with ref $ref — take a new snapshot';" +
            "e.scrollIntoView({block:'center'});var tag=e.tagName;e.focus&&e.focus();e.click();return 'clicked '+tag.toLowerCase()+' '+(e.innerText||e.value||'').slice(0,60);})()")
        settle(t, 8_000)
        return r + "\n" + statusLine(t)
    }

    suspend fun type(t: BrowserTab, ref: Int, text: String, submit: Boolean, clear: Boolean = true): String {
        val q = JSONObject.quote(text)
        val r = evalJson(t, "(function(){var e=document.querySelector('[data-hx=\"$ref\"]');if(!e)return 'no element with ref $ref — take a new snapshot';" +
            "e.scrollIntoView({block:'center'});e.focus();var t=$q;" +
            "if(e.isContentEditable){e.innerText=" + (if (clear) "t" else "e.innerText+t") + ";}else{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');" +
            "var nv=" + (if (clear) "t" else "(e.value||'')+t") + ";if(d&&d.set)d.set.call(e,nv);else e.value=nv;}" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
            (if (submit) "var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};e.dispatchEvent(new KeyboardEvent('keydown',k));e.dispatchEvent(new KeyboardEvent('keypress',k));e.dispatchEvent(new KeyboardEvent('keyup',k));" +
                "if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}" else "") +
            "return 'typed into '+e.tagName.toLowerCase();})()")
        if (submit) settle(t, 10_000) else delay(200)
        return r + "\n" + statusLine(t)
    }

    suspend fun select(t: BrowserTab, ref: Int, value: String): String {
        val q = JSONObject.quote(value)
        val r = evalJson(t, "(function(){var e=document.querySelector('[data-hx=\"$ref\"]');if(!e||e.tagName!=='SELECT')return 'ref $ref is not a select';" +
            "var v=$q;var o=Array.from(e.options).find(function(o){return o.value===v||o.text.trim()===v});if(!o)return 'no option '+v;e.value=o.value;" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'selected '+o.text;})()")
        return r
    }

    suspend fun pressKey(t: BrowserTab, key: String): String {
        val q = JSONObject.quote(key)
        val r = evalJson(t, "(function(){var k=$q;var e=document.activeElement||document.body;var code={Enter:13,Escape:27,Tab:9,Backspace:8,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39,' ':32}[k]||0;" +
            "['keydown','keypress','keyup'].forEach(function(n){e.dispatchEvent(new KeyboardEvent(n,{key:k,keyCode:code,which:code,bubbles:true}))});" +
            "if(k==='Enter'&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();}return 'pressed '+k;})()")
        settle(t, 6_000)
        return r
    }

    suspend fun scroll(t: BrowserTab, direction: String, amount: Int): String {
        val dy = when (direction) { "up" -> -amount; "top" -> -1_000_000; "bottom" -> 1_000_000; else -> amount }
        return evalJson(t, "(function(){window.scrollBy(0,$dy);return 'scrollY='+Math.round(window.scrollY)+' / '+document.documentElement.scrollHeight;})()")
    }

    suspend fun text(t: BrowserTab, maxChars: Int): String = evalJson(t, "document.body?document.body.innerText:''").take(maxChars)

    // ---------------------------------------------------------------- screenshots / cookies

    suspend fun screenshot(t: BrowserTab): File = withContext(Dispatchers.Main) {
        val w = t.web.width.coerceAtLeast(1); val h = t.web.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.web.draw(Canvas(bmp))
        val f = File(shotsDir, "shot_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        f
    }

    /**
     * A low-resolution visual-only preview. The authenticated tab stays parked.
     * No cookies, credentials or DOM text are transferred to the chat model.
     */
    suspend fun thumbnail(t: BrowserTab, width: Int = 320, height: Int = 200): Bitmap =
        withContext(Dispatchers.Main) {
            val w = width.coerceIn(64, 512)
            val h = height.coerceIn(64, 512)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val vw = t.web.width.coerceAtLeast(1)
            val vh = t.web.height.coerceAtLeast(1)
            canvas.save()
            canvas.scale(w.toFloat() / vw, h.toFloat() / vh)
            t.web.draw(canvas)
            canvas.restore()
            bitmap
        }

    /** Cookie header for the tab's site, written to a file so values never enter the model's context. */
    suspend fun cookiesFile(t: BrowserTab): Pair<List<String>, File?> = withContext(Dispatchers.Main) {
        val raw = CookieManager.getInstance().getCookie(t.url).orEmpty()
        val names = raw.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
        if (raw.isBlank()) return@withContext names to null
        val f = File(shotsDir, "cookies_${System.currentTimeMillis()}.env")
        f.writeText("export COOKIE_HEADER='${raw.replace("'", "'\\''")}'\n")
        names to f
    }

    suspend fun setCookie(t: BrowserTab, name: String, value: String, domain: String?, path: String, secure: Boolean, httpOnly: Boolean): Boolean = withContext(Dispatchers.Main) {
        val host = domain ?: try { URI(t.url).host } catch (_: Exception) { null } ?: return@withContext false
        val c = buildString {
            append("$name=$value; Domain=$host; Path=$path"); if (secure) append("; Secure"); if (httpOnly) append("; HttpOnly")
        }
        CookieManager.getInstance().setCookie("https://$host", c)
        CookieManager.getInstance().flush()
        true
    }

    // ---------------------------------------------------------------- hand-over to the user

    private val _handover = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Non-null while the agent waits for the user to finish something in the browser (the message to show). */
    val handover: kotlinx.coroutines.flow.StateFlow<String?> = _handover
    private val _showRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumps when the agent wants the browser on screen. */
    val showRequests: kotlinx.coroutines.flow.StateFlow<Int> = _showRequests
    @Volatile private var handoverDone: CompletableDeferred<Boolean>? = null

    suspend fun requestHandover(message: String): Boolean {
        val d = CompletableDeferred<Boolean>().also { handoverDone = it }
        _handover.value = message
        _showRequests.value++
        rt.notifyIfBackground("Hermes needs you in the browser", message)
        val ok = withTimeoutOrNull(10 * 60_000L) { d.await() } ?: false
        _handover.value = null; handoverDone = null
        return ok
    }

    fun finishHandover() { handoverDone?.complete(true) }

    // ---------------------------------------------------------------- on-screen mirror

    /** Detach the tab's WebView from any parent and return it for display. */
    fun viewFor(t: BrowserTab): WebView {
        (t.web.parent as? android.view.ViewGroup)?.removeView(t.web)
        return t.web
    }

    /** Called when the on-screen view goes away: restore the off-screen layout the automation relies on. */
    fun release(t: BrowserTab) {
        (t.web.parent as? android.view.ViewGroup)?.removeView(t.web)
        park(t)
        t.web.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
        t.web.layout(0, 0, VIEW_W, VIEW_H)
    }

    companion object {
        const val MAX_TABS = 5
        const val VIEW_W = 1080
        const val VIEW_H = 1920

        /** Numbers every visible interactive element (data-hx) and returns a compact outline + page text. */
        val SNAPSHOT_JS = """
(function(){
  var out=[],n=0;
  function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&parseFloat(s.opacity||'1')>0.05;}
  document.querySelectorAll('[data-hx]').forEach(function(e){e.removeAttribute('data-hx');});
  var sel='a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=switch],[onclick],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
  document.querySelectorAll(sel).forEach(function(e){
    if(n>=150||!vis(e))return;
    if(e.tagName==='INPUT'&&e.type==='hidden')return;
    n++;e.setAttribute('data-hx',n);
    var tag=e.tagName.toLowerCase(),type=e.type&&tag==='input'?'['+e.type+']':'';
    var label=(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.value||e.title||e.alt||'').replace(/\s+/g,' ').trim().slice(0,80);
    var extra='';
    if(tag==='a')extra=' → '+(e.getAttribute('href')||'').slice(0,100);
    if(tag==='input'||tag==='textarea'){if(e.type==='checkbox'||e.type==='radio')extra=e.checked?' (checked)':' (unchecked)';else if(e.value&&e.type!=='password')extra=' value="'+e.value.slice(0,40)+'"';}
    if(tag==='select')extra=' options: '+Array.from(e.options).slice(0,8).map(function(o){return o.text.trim()}).join(' | ');
    if(e.disabled)extra+=' (disabled)';
    out.push('['+n+'] '+tag+type+' "'+label+'"'+extra);
  });
  var heads=Array.from(document.querySelectorAll('h1,h2,h3')).filter(vis).slice(0,12).map(function(h){return '# '+h.innerText.replace(/\s+/g,' ').trim().slice(0,100)});
  var txt=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
  return 'viewport '+innerWidth+'x'+innerHeight+', scrollY='+Math.round(scrollY)+'/'+document.documentElement.scrollHeight+'\n'+
    (heads.length?'headings:\n'+heads.join('\n')+'\n':'')+'interactive elements:\n'+(out.join('\n')||'(none)')+'\n\npage text:\n'+txt.slice(0,5000);
})()
""".trimIndent()
    }
}
