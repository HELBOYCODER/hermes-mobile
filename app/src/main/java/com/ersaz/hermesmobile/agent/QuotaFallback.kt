package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.core.ModelProfile

/**
 * Quota handoff (Sno concept, Hermes implementation): when a provider
 * call fails with a quota/rate-limit error, the run AUTOMATICALLY
 * switches to the next configured provider (the user's own providers
 * only — never a shared account) and resumes from the last
 * checkpoint (see RunCheckpoint), not from scratch. The user is told
 * exactly which provider took over. If no fallback provider is
 * configured, the run stops honestly with "quota reached, no fallback
 * provider configured" instead of faking progress.
 *
 * Detection is a message heuristic (429 / quota / rate-limit /
 * FreeUsageLimitError / RESOURCE_EXHAUSTED) because providers surface
 * quota failures differently; the switch itself is real (settings
 * provider/model change + one retry of the same turn).
 */
object QuotaFallback {
    private val QUOTA_MARKERS = listOf("429", "quota", "freeusagelimiterror", "rate limit", "rate_limit", "resource_exhausted", "insufficient_quota")

    fun looksLikeQuota(message: String): Boolean {
        val m = message.lowercase()
        return QUOTA_MARKERS.any { it in m }
    }

    /** Next fallback: a different backend with a key, free ones first. */
    fun nextProvider(rt: HermesRuntime): ModelProfile? {
        val current = rt.settings.baseUrl.trimEnd('/')
        val seen = mutableSetOf<String>()
        return rt.modelStore.all()
            .filter { it.baseUrl.trimEnd('/') != current && it.baseUrl.trimEnd('/') !in seen }
            .onEach { seen.add(it.baseUrl.trimEnd('/')) }
            .filter { it.apiKey.isNotBlank() }
            .sortedWith(compareByDescending<ModelProfile> { it.free }.thenBy { it.label })
            .firstOrNull()
    }
}
