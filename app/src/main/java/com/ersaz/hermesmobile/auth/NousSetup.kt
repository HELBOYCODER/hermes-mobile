package com.ersaz.hermesmobile.auth

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.llm.LlmException
import com.ersaz.hermesmobile.llm.ModelCatalog
import com.ersaz.hermesmobile.llm.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a signed-in account can use, and what the app switched itself to. */
data class NousRoute(
    val tier: String,        // paid | free | welcome
    val baseUrl: String,
    val model: String,
    val contextLength: Int,
    val message: String,
)

/**
 * After sign-in: find out what this account may actually call, then pick a working model.
 * Free Nous accounts get no paid inference, so we fall back to the free models the same API
 * lists (`:free` / zero-priced, with tool calling) and finally to the `nous/welcome` model.
 */
class NousSetup(private val rt: HermesRuntime) {

    suspend fun configure(): NousRoute {
        val catalog = rt.refreshNousModels()

        // 1. Paid access? Probe one cheap call with a sensible default model.
        val paid = ModelCatalog.pickPaidDefault(catalog)
        var denied = paid == null
        if (paid != null) {
            when (probe(NousPortal.INFERENCE, paid.id)) {
                Probe.OK -> return apply(NousRoute("paid", NousPortal.INFERENCE, paid.id, paid.contextLength,
                    "Connected. Full Nous catalog unlocked — using ${paid.name}."), catalog.size)
                Probe.DENIED -> denied = true
                Probe.FAILED -> throw NousAuthException("Signed in, but the inference API did not answer. Check your connection and try again.", "probe_failed")
            }
        }

        // 2. Free models advertised on the same API.
        for (m in ModelCatalog.freeAgentModels(catalog).take(MAX_FREE_PROBES)) {
            if (probe(NousPortal.INFERENCE, m.id) == Probe.OK) {
                return apply(NousRoute("free", NousPortal.INFERENCE, m.id, m.contextLength,
                    "Connected on the free tier. Using free model ${m.name}" + (if (denied) " — subscribe on Nous Portal to unlock the rest." else ".")),
                    catalog.count { it.free && it.supportsTools })
            }
        }

        // 3. The welcome host serves one free model to any signed-in account.
        if (probe(NousPortal.WELCOME, WELCOME_MODEL) == Probe.OK) {
            return apply(NousRoute("welcome", NousPortal.WELCOME, WELCOME_MODEL, 128_000,
                "Connected on the free tier (Nous Welcome model). Subscribe on Nous Portal to unlock more models."), 1)
        }
        throw NousAuthException("Signed in, but this account has no usable model right now. Subscribe at ${NousPortal.PORTAL}/billing or add an OpenRouter key (it offers free models too).", "no_model")
    }

    private fun apply(route: NousRoute, freeCount: Int): NousRoute {
        val s = rt.settings
        s.provider = "nous"
        s.baseUrl = route.baseUrl
        s.model = route.model
        s.nousRoute = route.tier
        if (route.tier == "welcome") rt.modelStore.upsert(com.ersaz.hermesmobile.core.ModelProfile(
            "Nous / Hermes", "nous", NousPortal.WELCOME, "", WELCOME_MODEL, true, "nous-catalog", "free"))
        if (route.contextLength > 0) s.contextLength = route.contextLength.coerceAtMost(200_000)
        return route.copy(message = route.message)
    }

    private enum class Probe { OK, DENIED, FAILED }

    private suspend fun probe(baseUrl: String, model: String): Probe = try {
        LlmClient(rt.http, baseUrl, "", "nous", rt.nousKeyProvider)
            .chat(model, listOf(Message("user", "Reply with OK.")), null, stream = false, maxTokens = 16)
        Probe.OK
    } catch (e: LlmException) {
        // 400/404 mean "this model isn't served here": try the next candidate. Auth/billing errors mean no access.
        when {
            e.isEntitlementError() -> Probe.DENIED
            e.status in setOf(400, 404, 422) -> Probe.DENIED
            else -> Probe.FAILED
        }
    } catch (e: CancellationException) { throw e
    } catch (e: NousAuthException) { throw e
    } catch (_: Exception) { Probe.FAILED }

    companion object {
        const val WELCOME_MODEL = "nous/welcome"
        const val MAX_FREE_PROBES = 4
    }
}

sealed class LoginState {
    data object Idle : LoginState()
    data class AwaitingBrowser(val code: DeviceCode) : LoginState()
    data object Configuring : LoginState()
    data class Connected(val route: NousRoute) : LoginState()
    data class Failed(val message: String) : LoginState()
}

/** Runs the sign-in on the app scope so it survives screen rotation and the trip to the browser. */
class NousLogin(private val rt: HermesRuntime) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state
    private var job: Job? = null

    fun start(openBrowser: (String) -> Unit) {
        if (job?.isActive == true) return
        job = rt.scope.launch {
            try {
                val dc = rt.nousAuth.requestDeviceCode()
                _state.value = LoginState.AwaitingBrowser(dc)
                openBrowser(dc.verificationUriComplete)
                rt.nousAuth.awaitApproval(dc)
                _state.value = LoginState.Configuring
                val route = NousSetup(rt).configure()
                _state.value = LoginState.Connected(route)
                // "Back-link": nudge the user back to the app once the browser step is done.
                rt.notify("Connected to Nous Portal", route.message)
                rt.bringToFront()
            } catch (e: CancellationException) {
                _state.value = LoginState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = LoginState.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Re-run model selection for an already signed-in account. */
    fun reconfigure() {
        if (job?.isActive == true) return
        job = rt.scope.launch {
            _state.value = LoginState.Configuring
            _state.value = try { LoginState.Connected(NousSetup(rt).configure()) }
            catch (e: CancellationException) { LoginState.Idle }
            catch (e: Exception) { LoginState.Failed(e.message ?: e.toString()) }
        }
    }

    fun cancel() { job?.cancel(); _state.value = LoginState.Idle }

    fun reset() { if (job?.isActive != true) _state.value = LoginState.Idle }

    fun logout() { cancel(); rt.nousAuth.logout(); rt.settings.nousRoute = "" }
}
