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
    suspend fun installAndSetup(selectAsProvider: Boolean = true): Boolean = lock.withLock {
        if (!rt.sandbox.isInstalled() && !rt.sandbox.ensureInstalled()) { fail("The Linux sandbox is not ready yet."); return false }
        // Validate and repair dependencies even when an older installation exists.
        _state.value = State.INSTALLING
        say("Preparing Linux prerequisites (Node.js, npm, Python, Git, TLS and build tools)…")
        val prerequisites = rt.sandbox.exec(
            "missing=0; for cmd in node npm python3 pip3 git curl bash cc make openssl update-ca-certificates; do command -v \"${'$'}cmd\" >/dev/null 2>&1 || missing=1; done; " +
                "if [ \"${'$'}missing\" = 1 ]; then apk add --no-cache ca-certificates bash curl git python3 py3-pip nodejs npm build-base linux-headers openssl || exit ${'$'}?; fi; " +
                "update-ca-certificates 2>&1 && " +
                "node --version && npm --version && python3 --version && git --version",
            timeoutSec = 1800, onOutput = { appendLog(it) },
        )
        if (prerequisites.exitCode != 0) {
            fail("Prerequisite setup failed (exit ${prerequisites.exitCode}). Check the connection and retry; your data is kept.")
            return false
        }
        say("Installing bundled 9Router server and SQLite runtime…")
        if (!rt.sandbox.installBundledRouter()) {
            fail("The bundled router could not be installed. Check free storage and retry.")
            return false
        }
        try {
            startLocked() && configureLocked(selectAsProvider)
        } catch (e: kotlinx.coroutines.CancellationException) {
            proc?.let { rt.processes.kill(it.id) }; proc = null
            _state.value = State.STOPPED
            throw e
        } catch (e: Exception) {
            fail("9Router setup failed: ${e.message}")
            false
        }
    }

    suspend fun start(): Boolean = lock.withLock { startLocked() }

    private suspend fun startLocked(): Boolean {
        if (!installed()) { _state.value = State.NOT_INSTALLED; return false }
        if (api().isUp()) { _state.value = State.RUNNING; return true }
        _state.value = State.STARTING
        if (s.routerPassword.isBlank()) s.routerPassword = UUID.randomUUID().toString().replace("-", "").take(20)
        say("Starting 9Router on ${s.routerHost}:${s.routerPort}…")
        val routerDir = "/usr/lib/node_modules/9router"
        val nodePath = "/usr/lib/hermes-9router-runtime/node_modules:$routerDir/app/node_modules:$routerDir/node_modules"
        val cmd = "cd $routerDir/app && " +
            "INITIAL_PASSWORD=${Sandbox.shQuote(s.routerPassword)} " +
            "NODE_PATH=${Sandbox.shQuote(nodePath)} HOSTNAME=${Sandbox.shQuote(s.routerHost)} PORT=${s.routerPort} " +
            "exec node --dns-result-order=ipv4first --max-old-space-size=512 $routerDir/app/custom-server.js"
        proc?.let { rt.processes.kill(it.id) }
        proc = rt.processes.start(cmd, "/root")
        // First launch installs 9Router's own runtime dependencies, so give it time.
        repeat(120) {
            if (api().isUp()) { _state.value = State.RUNNING; say("9Router is running at $apiUrl"); return true }
            if (proc?.isRunning == false) {
                fail("9Router exited: " + (rt.processes.log(proc!!.id, 8) ?: "")); return false
            }
            delay(2000)
        }
        proc?.let { rt.processes.kill(it.id) }; proc = null
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
        if (!s.routerAutoStart) return
        rt.scope.launch(Dispatchers.IO) { installAndSetup(selectAsProvider = false) }
    }

    suspend fun refreshFreeModels(selectAsProvider: Boolean = false): Boolean = lock.withLock { configureLocked(selectAsProvider) }

    private suspend fun configureLocked(selectAsProvider: Boolean): Boolean {
        val a = api()
        try {
            say("Connecting OpenCode Free…")
            val panel = a.loginPanel(s.routerPassword)
                ?: run { fail("Could not sign in to the dashboard. Use the password shown in Settings → 9Router, or the password you changed in the dashboard."); return false }
            s.routerPassword = panel.password
            val cookie = panel.cookie
            if (s.routerKey.isBlank()) s.routerKey = a.createKey(cookie, "hermes-mobile") ?: run { fail("Could not create an API key."); return false }
            // OpenCode Free is a built-in no-auth lane; no provider connection or Zen key is needed.

            say("Fetching OpenCode Free models…")
            val catalog = a.openCodeFreeModels(cookie, s.routerKey)
            val free = catalog.models
            if (free.isEmpty()) { fail("OpenCode Free returned no models."); return false }
            rt.modelStore.replaceSource("9router", free.map {
                ModelProfile("9Router · OpenCode Free", "9router", apiUrl, s.routerKey, it, free = true, source = "9router")
            })

            // Pick the first free model that actually answers right now.
            var working: String? = null
            var lastErr: String? = null
            for (m in if (selectAsProvider) free.take(12) else emptyList()) {
                val err = a.probe(s.routerKey, m)
                if (err == null) { working = m; break } else lastErr = err
            }
            if (selectAsProvider) {
                rt.modelStore.ensureCurrent("Previous")
                val pick = working ?: free.first()
                rt.modelStore.select(ModelProfile("9Router · OpenCode Free", "9router", apiUrl, s.routerKey, pick, true, "9router"))
                s.routerConfigured = true
            }
            say(
                if (!selectAsProvider) "Ready: ${free.size} OpenCode Free models listed." + (if (catalog.live) "" else " Live catalog unavailable; showing bundled models.")
                else if (working != null) "Ready: ${free.size} OpenCode Free models, using $working."
                else "${free.size} OpenCode Free models listed, but none answered just now (${lastErr ?: "unknown"}). " +
                    "Free tiers change often — connect your own providers in the 9Router dashboard."
            )
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
