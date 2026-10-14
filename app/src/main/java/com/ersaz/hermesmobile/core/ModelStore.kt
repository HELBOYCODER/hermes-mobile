package com.ersaz.hermesmobile.core

import org.json.JSONArray
import org.json.JSONObject

/** One selectable model: which provider endpoint + key + model id to use. */
data class ModelProfile(
    val label: String,       // provider name shown in the list (e.g. "9Router", "OpenRouter")
    val providerId: String,  // Providers id ("custom" for ad-hoc endpoints)
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val free: Boolean = false,
    val source: String = "manual", // manual | fetched | 9router
) {
    val id get() = "$baseUrl|$model"
    fun toJson() = JSONObject().put("label", label).put("provider", providerId).put("base_url", baseUrl)
        .put("api_key", apiKey).put("model", model).put("free", free).put("source", source)

    companion object {
        fun fromJson(o: JSONObject) = ModelProfile(
            o.optString("label"), o.optString("provider", "custom"), o.optString("base_url"), o.optString("api_key"),
            o.optString("model"), o.optBoolean("free"), o.optString("source", "manual"),
        )
    }
}

/** The list behind the chat's model picker; persisted in [Settings.modelProfiles]. */
class ModelStore(private val settings: Settings) {
    fun all(): List<ModelProfile> = runCatching {
        val arr = JSONArray(settings.modelProfiles)
        (0 until arr.length()).map { ModelProfile.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<ModelProfile>) {
        settings.modelProfiles = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()
    }

    fun upsert(p: ModelProfile) {
        val l = all().toMutableList()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l += p
        save(l)
    }

    fun upsertAll(ps: List<ModelProfile>) {
        val l = all().associateBy { it.id }.toMutableMap()
        ps.forEach { l[it.id] = it }
        save(l.values.toList())
    }

    fun remove(id: String) = save(all().filter { it.id != id })

    /** Replace every entry that came from [source] (used when the 9Router free list is refreshed). */
    fun replaceSource(source: String, ps: List<ModelProfile>) = save(all().filter { it.source != source } + ps)

    fun isActive(p: ModelProfile) = settings.baseUrl.trimEnd('/') == p.baseUrl.trimEnd('/') && settings.model == p.model

    /** Make [p] the model used by chat. */
    fun select(p: ModelProfile) {
        settings.provider = p.providerId
        settings.baseUrl = if (p.providerId == "nous") com.ersaz.hermesmobile.auth.NousPortal.safeInferenceUrl(p.baseUrl) ?: com.ersaz.hermesmobile.auth.NousPortal.INFERENCE else p.baseUrl
        settings.apiKey = if (p.providerId == "nous") "" else p.apiKey
        settings.model = p.model
    }

    /** Keep the currently configured model in the list so it can always be switched back to. */
    fun ensureCurrent(label: String) {
        if (settings.model.isBlank() || settings.baseUrl.isBlank()) return
        val cur = ModelProfile(label, settings.provider, settings.baseUrl, settings.apiKey, settings.model)
        if (all().none { it.id == cur.id }) upsert(cur)
    }
}
