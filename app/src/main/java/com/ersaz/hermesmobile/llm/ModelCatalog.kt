package com.ersaz.hermesmobile.llm

import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.strings
import org.json.JSONObject

/** One entry of an OpenAI-style `/models` listing, with the OpenRouter/Nous extras (pricing, context, capabilities). */
data class ModelInfo(
    val id: String,
    val name: String,
    val contextLength: Int,
    val promptPrice: Double?,      // USD per token; null = not advertised
    val completionPrice: Double?,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean,
) {
    /** Free = explicitly priced at zero, or the conventional `:free` variant. */
    val free: Boolean get() = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)

    /** "$0.30 / $1.20 per 1M tokens", "free", or "" when the provider doesn't say. */
    fun priceLabel(): String = when {
        free -> "free"
        promptPrice == null || completionPrice == null -> ""
        else -> "$%.2f / $%.2f per 1M".format(promptPrice * 1_000_000, completionPrice * 1_000_000)
    }
}

object ModelCatalog {

    fun parse(body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return data.objects().mapNotNull { m ->
            val id = m.optString("id").trim()
            if (id.isEmpty() || id.endsWith(":batch")) return@mapNotNull null
            // Chat agents need a text-out model; skip pure image/audio generators when the listing says so.
            val outputs = m.optJSONObject("architecture")?.optJSONArray("output_modalities")?.strings()
            if (outputs != null && outputs.isNotEmpty() && "text" !in outputs) return@mapNotNull null
            val pricing = m.optJSONObject("pricing")
            val params = m.optJSONArray("supported_parameters")?.strings()?.toSet().orEmpty()
            val ctx = m.optInt("context_length", 0).takeIf { it > 0 }
                ?: m.optJSONObject("top_provider")?.optInt("context_length", 0) ?: 0
            ModelInfo(
                id = id,
                name = m.optString("name").ifBlank { id },
                contextLength = ctx,
                promptPrice = price(pricing, "prompt"),
                completionPrice = price(pricing, "completion"),
                // Listings without `supported_parameters` (plain OpenAI-compatible servers): assume tools work.
                supportsTools = params.isEmpty() || "tools" in params,
                supportsReasoning = "reasoning" in params || "include_reasoning" in params,
            )
        }.distinctBy { it.id }
    }

    private fun price(pricing: JSONObject?, key: String): Double? {
        if (pricing == null || !pricing.has(key) || pricing.isNull(key)) return null
        return pricing.optString(key).toDoubleOrNull()?.takeIf { it >= 0 }
    }

    /** Free models an agent can actually use (tool calling), roomiest context first. */
    fun freeAgentModels(models: List<ModelInfo>): List<ModelInfo> =
        models.filter { it.free && it.supportsTools }
            .sortedWith(compareByDescending<ModelInfo> { it.contextLength.coerceAtMost(256_000) }
                .thenBy { it.id.startsWith("stealth/") }.thenBy { it.id })

    /**
     * Default model for a paying account: a capable mid-priced tool-calling model, not the most
     * expensive one. Falls back to the first tool-capable model.
     */
    fun pickPaidDefault(models: List<ModelInfo>): ModelInfo? {
        val paid = models.filter { !it.free && it.supportsTools && !it.id.startsWith("~") }
        for (hint in listOf("sonnet", "gpt-", "gemini", "qwen", "deepseek", "glm", "kimi")) {
            paid.firstOrNull { hint in it.id.lowercase() }?.let { return it }
        }
        return paid.firstOrNull()
    }
}
