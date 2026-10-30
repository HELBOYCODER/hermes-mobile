package com.ersaz.hermesmobile.llm

/** How the user connects their OWN account to this provider. */
enum class LoginKind {
    /** Verified official account login (Nous device-code, OpenRouter PKCE). */
    LOGIN,
    /** API key only — the user enters their own key, nothing else. */
    KEY,
    /** No external account: models come from the local 9Router. */
    ROUTER,
    /** A login was wanted but no official third-party endpoint could be
     *  verified — the panel shows key entry only, never a fake Login. */
    LOGIN_UNVERIFIED,
    /** User-typed name + base URL + key (e.g. a cline2api-style bridge). */
    CUSTOM,
}

/**
 * The complete provider catalog. Every base URL below is reused verbatim
 * from the app's pre-existing Providers list — none is invented here.
 * Each entry carries its display name, official backend base URL, login
 * type, and a note (including the honest caveats for Anthropic and Z.ai).
 */
data class CatalogEntry(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val loginKind: LoginKind,
    val defaultModel: String,
    val keyHint: String,
    val note: String,
)

object ProviderCatalog {
    val all = listOf(
        // ---- Sign in --------------------------------------------------
        CatalogEntry("nous", "Nous Portal", "https://inference-api.nousresearch.com/v1",
            LoginKind.LOGIN, "Hermes-4-405B", "account login only",
            "Existing device-code flow, untouched. Free models included with a free account."),
        CatalogEntry("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            LoginKind.LOGIN, "nousresearch/hermes-4-405b", "sk-or-…",
            "Official OAuth PKCE flow (headless mode): OpenRouter shows a one-time authorization code; Hermes exchanges it for your own key. Your requests bill to your own credits."),
        CatalogEntry("9router", "OpenCode free via 9Router", "http://127.0.0.1:20128/v1",
            LoginKind.ROUTER, "", "(auto-created)",
            "No external account. Models come from the local 9Router's OpenCode Free fetch. The upstream OpenCode free-tier daily quota still applies."),
        // ---- API key --------------------------------------------------
        CatalogEntry("zai", "Z.ai", "https://api.z.ai/api/paas/v4",
            LoginKind.LOGIN_UNVERIFIED, "glm-4.5", "key",
            "UNVERIFIED — key entry only. No official third-party OAuth endpoint for Z.ai could be verified (docs.z.ai publishes API-key creation only), so no Z.ai Login is offered. Coding Plan keys use https://api.z.ai/api/coding/paas/v4 and are not interchangeable with standard keys."),
        CatalogEntry("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            LoginKind.KEY, "deepseek-chat", "sk-…",
            "Your own DeepSeek API key. Thinking models accept only low/high/max effort (mapped automatically)."),
        CatalogEntry("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            LoginKind.KEY, "gemini-2.5-flash", "AIza…",
            "Official Google AI API only. Consumer Antigravity sessions and the Antigravity CLI are never used as a provider here (see issue #12)."),
        CatalogEntry("mistral", "Mistral", "https://api.mistral.ai/v1",
            LoginKind.KEY, "mistral-large-latest", "key",
            "Your own Mistral API key."),
        CatalogEntry("groq", "Groq", "https://api.groq.com/openai/v1",
            LoginKind.KEY, "llama-3.3-70b-versatile", "gsk_…",
            "Your own Groq API key."),
        CatalogEntry("openai", "OpenAI", "https://api.openai.com/v1",
            LoginKind.KEY, "gpt-4.1", "sk-…",
            "Your own OpenAI Platform API key (not a ChatGPT consumer session)."),
        CatalogEntry("anthropic", "Anthropic", "https://api.anthropic.com/v1",
            LoginKind.KEY, "claude-sonnet-4-5", "sk-ant-…",
            "Caveat: Anthropic's official API is Messages-format, not OpenAI-compatible — the app's OpenAI-style chat path and Bearer-authenticated /models fetch against it are unverified and may fail. The entry is kept with its official base URL; a failed fetch shows its real error."),
        CatalogEntry("xai", "xAI Grok", "https://api.x.ai/v1",
            LoginKind.KEY, "grok-4", "xai-…",
            "Your own xAI API key (not a consumer session)."),
        CatalogEntry("ollama", "Ollama (LAN/local)", "http://192.168.1.10:11434/v1",
            LoginKind.KEY, "hermes3", "(none)",
            "Local server on your LAN. No key needed; edit the URL to your machine's address."),
        CatalogEntry("lmstudio", "LM Studio (LAN/local)", "http://192.168.1.10:1234/v1",
            LoginKind.KEY, "local-model", "(none)",
            "Local server on your LAN. No key needed; edit the URL to your machine's address."),
        // ---- Custom ---------------------------------------------------
        CatalogEntry("custom", "Custom endpoint", "",
            LoginKind.CUSTOM, "", "key",
            "Your own bridge (e.g. a cline2api-style bridge): type its name, backend base URL, and your key."),
    )

    fun byId(id: String): CatalogEntry = all.firstOrNull { it.id == id } ?: all.first { it.id == "custom" }
}
