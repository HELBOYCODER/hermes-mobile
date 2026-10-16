package com.ersaz.hermesmobile.core

import org.json.JSONObject

/**
 * OpenCode Console publishes a public model catalog at /inference/v1/models, but the
 * different model families have different inference endpoints. The generic Hermes
 * client currently speaks OpenAI chat/completions, so only include free models whose
 * official documentation explicitly assigns that endpoint. A model listing does not
 * prove that an account is authorized to run it.
 */
object OpenCodeCatalog {
    const val MODELS_URL = "https://opencode.ai/inference/v1/models"
    const val CHAT_BASE_URL = "https://opencode.ai/inference/openai/v1"

    private val documentedFreeChatModels = setOf(
        "cat-jam-free",
        "ll-alpha-free",
        "mimo-v2.6-flash-free",
        "mimo-v2.5-free",
        "nemotron-3-ultra-free",
        "nemotron-3.5-lightning-free",
        "nemotron-3.5-super-vl-free",
        "longcat-2.5-preview-free",
        "deepseek-v4.1-flash-free",
        "glm-5.3-flash-free",
        "space-bunny-free",
        "exo-free",
    )

    fun freeChatIds(catalogJson: String): List<String> {
        val models = JSONObject(catalogJson).optJSONArray("data") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            models.optJSONObject(i)?.optString("id")?.takeIf { it in documentedFreeChatModels }
        }.distinct().sorted()
    }
}
