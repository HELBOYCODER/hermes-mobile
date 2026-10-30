package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.core.ModelProfile

/**
 * Task-based model routing (Copilot-harness concept, adopted into
 * Hermes' OWN native engine — real, but deliberately narrow and honest).
 * It routes a task to the best model among the user's ALREADY-CONFIGURED
 * providers, using task type only. It never invents a capability: a
 * suggestion is made only when a model id/name carries an obvious
 * marker (coder/deepseek for code); otherwise it returns null and the
 * user's current model stands. The user's explicit ModelPicker choice
 * always wins — the suggestion is a one-tap chip, never a silent switch.
 */
object TaskRouter {
    enum class TaskType { CODE, DESIGN, RESEARCH, GENERAL }

    fun classify(text: String): TaskType {
        val t = text.lowercase()
        return when {
            listOf("code", "fix", "bug", "implement", "build", "gradle", "kotlin", "function", "compile", "test", "کد", "برنامه", "باگ", "رفع").any { it in t } -> TaskType.CODE
            listOf("design", "prototype", "slide", "infographic", "animation", "poster", "deck", "طرح", "دیزاین", "پروتوتایپ", "اسلاید", "انیمیشن").any { it in t } -> TaskType.DESIGN
            listOf("search", "compare", "explain", "summar", "research", "find", "what is", "جستجو", "مقایسه", "توضیح", "خلاصه").any { it in t } -> TaskType.RESEARCH
            else -> TaskType.GENERAL
        }
    }

    /** Suggest a model for [text] from [models], or null (no suggestion). */
    fun suggest(text: String, models: List<ModelProfile>): ModelProfile? {
        if (models.isEmpty()) return null
        return when (classify(text)) {
            TaskType.CODE -> models.firstOrNull { m ->
                val hay = (m.model + " " + m.providerId).lowercase()
                "coder" in hay || "deepseek" in hay || "code" in hay
            }
            // DESIGN/RESEARCH/GENERAL: no reliable marker exists in a
            // model id, so no suggestion — inventing one would be fake routing.
            else -> null
        }
    }
}
