package com.ersaz.hermesmobile.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Where the Nous Portal OAuth session lives (Settings in the app, a fake in tests). */
interface NousTokenStore {
    var authMode: String
    var nousAccessToken: String
    var nousRefreshToken: String
    var nousExpiresAt: Long
    var nousScope: String
    var nousAccount: String
}

/**
 * App configuration — the mobile equivalent of ~/.hermes/config.yaml + .env.
 * Every write bumps [version] so Compose screens can recompose on change.
 */
class Settings(context: Context) : NousTokenStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply(); _version.value++
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply(); _version.value++
        }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getLong(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            prefs.edit().putLong(key, value).apply(); _version.value++
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply(); _version.value++
        }
    }

    // --- Model / provider -------------------------------------------------
    var provider by str("provider", "openrouter")
    var baseUrl by str("base_url", "https://openrouter.ai/api/v1")
    var apiKey by str("api_key", "")
    /** key = the API key above; nous_oauth = signed in to Nous Portal (device-code login, auto-refreshed). */
    override var authMode by str("auth_mode", "key")
    override var nousAccessToken by str("nous_access_token", "")
    override var nousRefreshToken by str("nous_refresh_token", "")
    override var nousExpiresAt by long("nous_expires_at", 0L)
    override var nousScope by str("nous_scope", "")
    override var nousAccount by str("nous_account", "")
    var nousRoute by str("nous_route", "") // paid | free | welcome — what the account may use
    var model by str("model", "nousresearch/hermes-4-405b")
    var auxModel by str("aux_model", "") // titles/compression; empty = main model
    var contextLength by int("context_length", 128_000)
    var maxIterations by int("max_iterations", 90)
    var maxTokens by int("max_tokens", 0) // 0 = provider default
    var reasoningEffort by str("reasoning_effort", "") // "", none, low, medium, high
    var showReasoning by bool("show_reasoning", true)
    var compressionThreshold by int("compression_threshold_pct", 50)
    var subAgentsEnabled by bool("sub_agents_enabled", true)
    var subModel by str("sub_model", "") // light model for sub-agents that pick model_choice=sub_model

    // --- Personality / display -------------------------------------------
    var personality by str("personality", "")
    var customSystemPrompt by str("system_prompt", "")
    var skin by str("skin", "nous")
    var toolProgress by str("tool_progress", "all") // off | new | all | verbose
    var showStatusBar by bool("status_bar", true)
    var timestamps by bool("timestamps", false)

    // --- Tools -------------------------------------------------------------
    var disabledToolsets by str("disabled_toolsets", "")
    var disabledTools by str("disabled_tools", "")
    var approvalMode by str("approval_mode", "off") // off (full access, default) | manual (ask before risky commands)
    var commandTimeout by int("command_timeout", 180)
    var webSearchBackend by str("web_search_backend", "duckduckgo") // duckduckgo | tavily | brave
    var tavilyKey by str("tavily_key", "")
    var braveKey by str("brave_key", "")
    var bindSharedStorage by bool("bind_storage", false)
    var prootNoSeccomp by bool("proot_no_seccomp", false)
    var sandboxEnv by str("sandbox_env", "") // KEY=VALUE per line, exported inside the Linux sandbox
    var apkMirror by str("apk_mirror", "") // base URL of an Alpine mirror, blank = official CDN
    var busyMode by str("busy_mode", "interrupt") // interrupt | queue — what a new message does while the agent is working
    var fontScale by str("font_scale", "1.0")
    var memoryEnabled by bool("memory_enabled", true)
    var userProfileEnabled by bool("user_profile_enabled", true)

    // --- Messaging gateway (Telegram / Discord / Slack) ------------------------
    var botMode by bool("bot_mode", false)
    var keepAwake by bool("keep_awake", true)
    var telegramToken by str("telegram_token", "")
    var telegramAllowed by str("telegram_allowed", "")
    var telegramRequireMention by bool("telegram_require_mention", true)
    var discordToken by str("discord_token", "")
    var discordAllowed by str("discord_allowed", "")
    var discordRequireMention by bool("discord_require_mention", true)
    var slackBotToken by str("slack_bot_token", "")
    var slackAppToken by str("slack_app_token", "")
    var slackAllowed by str("slack_allowed", "")
    var homeChannels by str("home_channels", "{}") // JSON {platform: chatId}
    var gatewayToolProgress by str("gateway_tool_progress", "new")

    // --- Voice input --------------------------------------------------------
    var voiceLang by str("voice_lang", "fa-IR") // fa-IR | en-US | auto

    // --- 9Router (local AI router running in the sandbox) -------------------
    var routerHost by str("router_host", "127.0.0.1")
    var routerPort by int("router_port", 20128)
    var routerAutoStart by bool("router_autostart", true)
    var routerPassword by str("router_password", "")
    var routerKey by str("router_key", "")
    var routerConfigured by bool("router_configured", false)

    // --- Saved models / providers shown in the chat model picker (JSON array) ---
    var modelProfiles by str("model_profiles", "[]")

    // --- GitHub -------------------------------------------------------------
    var githubToken by str("github_token", "")
    var githubUser by str("github_user", "")
    var githubName by str("github_name", "")
    var githubEmail by str("github_email", "")

    // --- Remote WebUI (legacy mode) ---------------------------------------
    var webUiUrl by str("webui_url", "")

    // --- Onboarding --------------------------------------------------------
    var onboarded by bool("onboarded", false)

    /** Parsed [sandboxEnv]: valid names only. */
    fun envVars(): Map<String, String> = sandboxEnv.lines().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim().takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }?.let { it to l.substring(i + 1).trim().trim('"') }
    }.toMap()

    fun fontScaleValue(): Float = fontScale.toFloatOrNull()?.coerceIn(0.8f, 1.5f) ?: 1f

    fun disabledToolsetSet(): Set<String> = splitList(disabledToolsets).toSet()
    fun disabledToolSet(): Set<String> = splitList(disabledTools).toSet()

    fun setToolsetEnabled(name: String, enabled: Boolean) {
        val s = disabledToolsetSet().toMutableSet()
        if (enabled) s.remove(name) else s.add(name)
        disabledToolsets = s.sorted().joinToString(",")
    }

    fun setToolEnabled(name: String, enabled: Boolean) {
        val s = disabledToolSet().toMutableSet()
        if (enabled) s.remove(name) else s.add(name)
        disabledTools = s.sorted().joinToString(",")
    }

    companion object {
        fun splitList(s: String): List<String> =
            s.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
