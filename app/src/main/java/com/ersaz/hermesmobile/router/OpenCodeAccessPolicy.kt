package com.ersaz.hermesmobile.router

/**
 * OpenCode Free availability must never be inferred from a 9Router model listing.
 * OpenCode's upstream free tier may be available only inside its own CLI.
 *
 * This boundary is deliberately pure so it can be tested without device/network access.
 */
enum class OpenCodeAccess {
    CATALOG_ONLY,
    CLI_LOGIN_REQUIRED,
    CLI_AUTHENTICATED,
    INFERENCE_VERIFIED,
    INFERENCE_RESTRICTED,
}

data class OpenCodeCapability(
    val access: OpenCodeAccess,
    val modelId: String? = null,
    val detail: String = "",
) {
    val selectable: Boolean get() = access == OpenCodeAccess.INFERENCE_VERIFIED

    val label: String get() = when (access) {
        OpenCodeAccess.CATALOG_ONLY -> "Listed only. Not verified for inference."
        OpenCodeAccess.CLI_LOGIN_REQUIRED -> "Sign in through the official OpenCode CLI."
        OpenCodeAccess.CLI_AUTHENTICATED -> "Signed in. Verify model inference before use."
        OpenCodeAccess.INFERENCE_VERIFIED -> "Available: inference tested successfully."
        OpenCodeAccess.INFERENCE_RESTRICTED -> "Restricted by upstream OpenCode access rules."
    }
}

object OpenCodeAccessPolicy {
    fun afterProbe(modelId: String, responseCode: Int?, error: String?): OpenCodeCapability {
        val restricted = responseCode == 401 || responseCode == 403 ||
            error.orEmpty().contains("FreeTierError", ignoreCase = true)
        return when {
            restricted -> OpenCodeCapability(OpenCodeAccess.INFERENCE_RESTRICTED, modelId, "OpenCode account/CLI restrictions")
            responseCode != null && responseCode in 200..299 ->
                OpenCodeCapability(OpenCodeAccess.INFERENCE_VERIFIED, modelId)
            else -> OpenCodeCapability(OpenCodeAccess.CATALOG_ONLY, modelId, error.orEmpty().take(200))
        }
    }
}
