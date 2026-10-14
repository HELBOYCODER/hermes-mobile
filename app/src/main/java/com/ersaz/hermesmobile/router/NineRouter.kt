package com.ersaz.hermesmobile.router

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.core.ModelProfile
import com.ersaz.hermesmobile.sandbox.ProcessRegistry
import com.ersaz.hermesmobile.sandbox.Sandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * 9Router (github.com/decolua/9router) running inside the Alpine sandbox and exposed to Hermes as an
 * OpenAI-compatible provider on a fixed local ip:port while the app is open.
 */
class NineRouter(private val rt: HermesRuntime) {
    enum class State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(if (installed()) State.STOPPED else State.NOT_INSTALLED)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val lock = Mutex()
    private var proc: ProcessRegistry.Entry? = null

    private val s get() = rt.settings
    val baseUrl get() = "http://${s.routerHost}:${s.routerPort}"
    val apiUrl get() = "$baseUrl/v1"
    val dashboardUrl get() = "$baseUrl/dashboard"
    private fun api() = RouterApi(rt.http, baseUrl)

    fun installed(): Boolean = listOf("usr/bin/9router", "usr/local/bin/9router", "usr/lib/node_modules/9router")
        .any { File(rt.sandbox.rootfs, it).exists() }

    private fun appendLog(t: String) { _log.value = (_log.value + t).takeLast(6000) }
    private fun say(t: String) { _message.value = t; appendLog("\n▸ $t\n") }

    /** The one-tap flow: install → start → connect free providers → select 9Router in Hermes → fetch free models. */
    suspend fun installAndSetup(): Boolean = lock.withLock {
        if (!rt.sandbox.isInstalled() && !rt.sandbox.ensureInstalled()) { fail("The Linux sandbox is not ready yet."); return false }
        if (!installed()) {
            _state.value = State.INSTALLING
            say("Installing Node.js and 9Router (this takes a few minutes)…")
            val r = rt.sandbox.exec(
                "apk add --no-cache nodejs npm 2>&1 && npm install -g 9router 2>&1", timeoutSec = 1800,
                onOutput = { appendLog(it) },
            )
            if (r.exitCode != 0 || !installed()) { fail("Install failed (exit ${r.exitCode}). See the log below."); return false }
        }
        startLocked() && configureLocked(selectAsProvider = true)
    }

    suspend fun start(): Boolean = lock.withLock { startLocked() }

    private suspend fun startLocked(): Boolean {
        if (!installed()) { _state.value = State.NOT_INSTALLED; return false }
        if (api().isUp()) { _state.value = State.RUNNING; return true }
        _state.value = State.STARTING
        if (s.routerPassword.isBlank()) s.routerPassword = UUID.randomUUID().toString().replace("-", "").take(20)
        say("Starting 9Router on ${s.routerHost}:${s.routerPort}…")
        val cmd = "INITIAL_PASSWORD=${Sandbox.shQuote(s.routerPassword)} exec 9router --no-browser --skip-update " +
            "-H ${Sandbox.shQuote(s.routerHost)} -p ${s.routerPort}"
        proc = rt.processes.start(cmd, "/root")
        // First launch installs 9Router's own runtime dependencies, so give it time.
        repeat(120) {
            if (api().isUp()) { _state.value = State.RUNNING; say("9Router is running at $apiUrl"); return true }
            if (proc?.isRunning == false) {
                fail("9Router exited: " + (rt.processes.log(proc!!.id, 8) ?: "")); return false
            }
            delay(2000)
        }
        fail("9Router did not answer on $baseUrl after 4 minutes.")
        return false
    }

    suspend fun stop() = lock.withLock {
        proc?.let { rt.processes.kill(it.id) }
        proc = null
        // A 9Router started by an earlier app run may still hold the port.
        runCatching { rt.sandbox.exec("pkill -f 9router || true", timeoutSec = 15) }
        _state.value = if (installed()) State.STOPPED else State.NOT_INSTALLED
        say("9Router stopped.")
    }

    /** Auto-start with the app, if installed and enabled. */
    fun autoStartIfNeeded() {
        if (!s.routerAutoStart || !installed()) return
        rt.scope.launch(Dispatchers.IO) { if (start()) refreshFreeModels(selectAsProvider = false) }
    }

    suspend fun refreshFreeModels(selectAsProvider: Boolean = false): Boolean = lock.withLock { configureLocked(selectAsProvider) }

    private suspend fun configureLocked(selectAsProvider: Boolean): Boolean {
        val a = api()
        try {
            say("Connecting free providers…")
            val cookie = a.login(s.routerPassword) ?: a.login("123456")
                ?: run { fail("Could not sign in to the 9Router dashboard (password was changed there?). Open the dashboard and create an API key, then paste it in Settings → 9Router."); return false }
            if (s.routerKey.isBlank()) s.routerKey = a.createKey(cookie, "hermes-mobile") ?: run { fail("Could not create an API key."); return false }
            val connected = a.connectedProviders(cookie)
            // OpenCode Zen's free tier accepts the public key; more providers can be added in the dashboard.
            if ("opencode-zen" !in connected) a.connectKeyProvider(cookie, "opencode-zen", "public")

            say("Fetching free models…")
            val free = a.models(s.routerKey).filter { RouterApi.isFree(it) }.distinct().sorted()
            if (free.isEmpty()) { fail("9Router returned no free models."); return false }
            rt.modelStore.replaceSource("9router", free.map {
                ModelProfile("9Router", "9router", apiUrl, s.routerKey, it, free = true, source = "9router")
            })

            // Pick the first free model that actually answers right now.
            var working: String? = null
            var lastErr: String? = null
            for (m in free.take(12)) {
                val err = a.probe(s.routerKey, m)
                if (err == null) { working = m; break } else lastErr = err
            }
            if (selectAsProvider || !s.routerConfigured) {
                rt.modelStore.ensureCurrent("Previous")
                val pick = working ?: free.first()
                rt.modelStore.select(ModelProfile("9Router", "9router", apiUrl, s.routerKey, pick, true, "9router"))
                s.routerConfigured = true
            }
            say(
                if (working != null) "Ready: ${free.size} free models, using $working."
                else "${free.size} free models listed, but none answered just now (${lastErr ?: "unknown"}). " +
                    "Free tiers change often — connect your own providers in the 9Router dashboard."
            )
            return true
        } catch (e: Exception) {
            fail("9Router setup failed: ${e.message}")
            return false
        }
    }

    private fun fail(msg: String) {
        if (_state.value != State.RUNNING) _state.value = State.ERROR
        say(msg)
    }
}
