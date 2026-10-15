package com.ersaz.hermesmobile.llm

import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.core.ModelProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

enum class ProbeStatus { RESPONDED, AUTH_ERROR, RESTRICTED, UNAVAILABLE, RATE_LIMITED, SERVER_ERROR, NETWORK_ERROR, TIMED_OUT }
data class ProbeResult(val profileId: String, val status: ProbeStatus, val latencyMs: Long, val checkedAtMs: Long, val detail: String)

class ModelProbe(private val clientFactory: (ModelProfile) -> LlmClient, private val nowMs: () -> Long) {
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(2)
    suspend fun test(profile: ModelProfile): ProbeResult {
        check(active.add(profile.id)) { "A test is already running for this model" }
        try {
            return slots.withPermit {
                val start = nowMs()
                var client: LlmClient? = null
                val status = try {
                    val probeClient = clientFactory(profile).also { client = it }
                    withTimeout(30_000) {
                        val result = probeClient.chat(profile.model, listOf(Message("user", "Reply with OK.")),
                            stream = false, maxTokens = 16, maxAttempts = 1)
                        if (result.content.isBlank()) ProbeStatus.UNAVAILABLE else ProbeStatus.RESPONDED
                    }
                } catch (_: TimeoutCancellationException) { client?.cancel(); ProbeStatus.TIMED_OUT
                } catch (e: CancellationException) { client?.cancel(); throw e
                } catch (e: LlmException) {
                    when {
                        e.status == 401 -> ProbeStatus.AUTH_ERROR
                        e.status == 403 -> ProbeStatus.RESTRICTED
                        e.status == 429 -> ProbeStatus.RATE_LIMITED
                        e.status >= 500 -> ProbeStatus.SERVER_ERROR
                        else -> ProbeStatus.UNAVAILABLE
                    }
                } catch (e: com.ersaz.hermesmobile.auth.NousAuthException) { ProbeStatus.AUTH_ERROR
                } catch (_: Exception) { ProbeStatus.NETWORK_ERROR }
                val end = nowMs()
                ProbeResult(profile.id, status, (end - start).coerceAtLeast(0), end,
                    when (status) {
                        ProbeStatus.RESPONDED -> "Text response received; tool support not tested."
                        ProbeStatus.RESTRICTED -> "Provider denied access to this model."
                        ProbeStatus.AUTH_ERROR -> "Sign in or check this provider's credentials."
                        else -> "Model test: ${status.name.lowercase().replace('_', ' ')}"
                    })
            }
        } finally { active.remove(profile.id) }
    }
}
