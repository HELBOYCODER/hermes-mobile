package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime

/**
 * Copilot (Foundry) engine option — Hermes' own code, no Microsoft UI,
 * branding, or proprietary code, and NO claim that Hermes runs
 * Microsoft's harness locally. It does not: the Copilot harness is a
 * managed Foundry Agent Service resource (public preview, announced
 * 2026-10-28), chosen at agent creation and not switchable afterwards.
 *
 * This entry is a REMOTE engine. It appears in the selector by default,
 * but it only runs when the user enters THEIR OWN Foundry/Azure project
 * endpoint + API key in Settings (BYOK, user's own account — no shared
 * account, no hardcoded endpoint/key). Until then it shows "needs your
 * Foundry account" and selecting it falls back to the native engine.
 *
 * STUB, by the task's own rule: the Foundry Copilot-harness invocation
 * surface for direct use from an app (exact project-endpoint shape +
 * Responses API payload for a harness agent) could NOT be verified
 * from official Microsoft/Foundry docs in this implementation pass
 * (the public docs describe portal/azd/SDK/REST authoring and the
 * Responses API on the Foundry project endpoint, but not a verified
 * app-side call shape). So this build SENDS NOTHING to Foundry even
 * when an endpoint + key are stored: status() says so plainly, and
 * the engine selector falls back to native. No endpoint or auth flow
 * is invented here. When a verified call shape exists, it belongs in
 * invoke() below — nowhere else.
 *
 * Preview/billing note shown on the entry (from the announcement):
 * harness runtime free until 2026-11-30, consumption billing from
 * 2026-12-01 (sandbox compute + memory metered; model inference and
 * separately billed tools are charged separately).
 */
object CopilotHarness {
    const val BILLING_NOTE =
        "Public preview — harness runtime free until 2026-11-30, consumption billing from 2026-12-01 (model inference charged separately)."
    const val DESCRIPTION =
        "Copilot (Foundry) — remote Microsoft harness, public preview. Needs your own Foundry account (endpoint + API key, BYOK). $BILLING_NOTE"

    data class Status(val configured: Boolean, val message: String)

    fun status(rt: HermesRuntime): Status {
        val s = rt.settings
        if (s.foundryEndpoint.isBlank() || s.foundryApiKey.isBlank()) return Status(false,
            "Copilot (Foundry) needs your Foundry account: enter your project endpoint + API key in Settings → Agent engine. Until then, chat runs the native engine — never a fake Copilot run.")
        return Status(false,
            "Foundry endpoint + key are stored, but the Copilot-harness invocation surface is not verified from official docs for direct app use, so this build sends nothing to Foundry. Chat runs the native engine. $BILLING_NOTE")
    }

    /** The single place a real Foundry call would go. Today: stub. */
    @Suppress("UNUSED_PARAMETER")
    fun invoke(rt: HermesRuntime, prompt: String): Status =
        Status(false, "Copilot (Foundry) remote call is a stub in this build (invocation surface unverified — see CopilotHarness). Nothing was sent; the native engine handles this chat.")
}
