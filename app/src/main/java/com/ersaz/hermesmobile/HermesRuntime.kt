package com.ersaz.hermesmobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ersaz.hermesmobile.agent.Message
import com.ersaz.hermesmobile.agent.MemoryStore
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.agent.SessionStore
import com.ersaz.hermesmobile.agent.SkillStore
import com.ersaz.hermesmobile.agent.tools.ToolRegistry
import com.ersaz.hermesmobile.auth.NousAuth
import com.ersaz.hermesmobile.auth.NousLogin
import com.ersaz.hermesmobile.bridge.LocalBridge
import com.ersaz.hermesmobile.core.Settings
import com.ersaz.hermesmobile.cron.CronRunner
import com.ersaz.hermesmobile.cron.CronStore
import com.ersaz.hermesmobile.gateway.GatewayManager
import com.ersaz.hermesmobile.llm.LlmClient
import com.ersaz.hermesmobile.sandbox.ProcessRegistry
import com.ersaz.hermesmobile.sandbox.Sandbox
import com.ersaz.hermesmobile.service.HermesService
import com.ersaz.hermesmobile.service.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Process-wide Hermes runtime: the Android counterpart of a running `hermes` + gateway. */
class HermesRuntime(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val nousAuth = NousAuth(http, settings)
    val nousLogin = NousLogin(this)
    /** Bearer supplier for Nous Portal OAuth sessions (refreshes on demand). */
    val nousKeyProvider: suspend (Boolean) -> String? = { force -> nousAuth.accessToken(force) }

    val sandbox = Sandbox(app, settings)
    val processes = ProcessRegistry(sandbox)

    /** $HERMES_HOME == /root/.hermes inside the sandbox rootfs. */
    val hermesHome: File = File(sandbox.rootfs, "root/.hermes").apply { mkdirs() }
    val sessions = SessionStore(File(hermesHome, "sessions"))
    val memory = MemoryStore(File(hermesHome, "memories"))
    val skills = SkillStore(File(hermesHome, "skills"))
    val cron = CronStore(File(hermesHome, "cron/jobs.json"))
    val bots = com.ersaz.hermesmobile.bots.BotStore(File(hermesHome, "bots"), memory)
    val botHub = com.ersaz.hermesmobile.bots.BotHub(this)
    val log = com.ersaz.hermesmobile.core.AppLog()
    val backup = com.ersaz.hermesmobile.backup.Backup(app, hermesHome)
    val usage = com.ersaz.hermesmobile.agent.UsageStore(File(hermesHome, "usage.json"))
    val dictation = com.ersaz.hermesmobile.service.Dictation(app)
    val permissions = com.ersaz.hermesmobile.device.Permissions(app)
    val browser = com.ersaz.hermesmobile.browser.BrowserManager(this)
    val agentJobs = com.ersaz.hermesmobile.agent.jobs.AgentJobs(this)
    val mcp = com.ersaz.hermesmobile.mcp.McpManager(this)
    val tools = ToolRegistry(this)
    val speech = Speech(app, File(hermesHome, "audio"))
    val gateway = GatewayManager(this)
    val cronRunner = CronRunner(this)
    val bridge = LocalBridge(this)
    val modelStore = com.ersaz.hermesmobile.core.ModelStore(settings)
    val router by lazy { com.ersaz.hermesmobile.router.NineRouter(this) }

    init {
        createChannels()
        scope.launch(Dispatchers.IO) {
            runCatching { SkillStore.installBundled(app, skills.dir) }
            val soul = File(hermesHome, "SOUL.md")
            if (!soul.exists()) soul.writeText(com.ersaz.hermesmobile.agent.PromptBuilder.DEFAULT_IDENTITY + "\n")
            if (sandbox.payloadAvailable()) sandbox.ensureInstalled()
            runCatching { com.ersaz.hermesmobile.sandbox.GitHubLink.apply(sandbox, settings) }
            bridge.start()
            router.autoStartIfNeeded()
        }
    }

    fun usesNousLogin(): Boolean = settings.provider == "nous" && nousAuth.signedIn

    fun llmClient(): LlmClient =
        if (settings.provider == "nous") LlmClient(http,
            com.ersaz.hermesmobile.auth.NousPortal.authenticatedEndpoint(nousAuth.signedIn, settings.baseUrl),
            "", "nous", nousKeyProvider)
        else LlmClient(http, settings.baseUrl, settings.apiKey, settings.provider)

    suspend fun refreshNousModels(): List<com.ersaz.hermesmobile.llm.ModelInfo> {
        val endpoint = com.ersaz.hermesmobile.auth.NousPortal.authenticatedEndpoint(nousAuth.signedIn,
            com.ersaz.hermesmobile.auth.NousPortal.INFERENCE)
        val catalog = LlmClient(http, endpoint, "", "nous", nousKeyProvider).listModelInfo().toMutableList()
        if (settings.nousRoute == "welcome" && catalog.none { it.id == com.ersaz.hermesmobile.auth.NousSetup.WELCOME_MODEL }) {
            catalog += com.ersaz.hermesmobile.llm.ModelInfo(com.ersaz.hermesmobile.auth.NousSetup.WELCOME_MODEL,
                "Nous Welcome", 128_000, 0.0, 0.0, true, false)
        }
        modelStore.replaceSource("nous-catalog", com.ersaz.hermesmobile.core.nousCatalogProfiles(catalog))
        return catalog
    }

    /** Best-effort return to the app after the browser step of a login (blocked by Android 10+ in the background; the notification covers that). */
    fun bringToFront() {
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Cheap side-call (titles, compression summaries) on the auxiliary or main model. */
    suspend fun auxComplete(prompt: String, maxTokens: Int = 1500): String? = try {
        val model = settings.auxModel.ifBlank { settings.model }
        llmClient().chat(model, listOf(Message("user", prompt)), null, stream = false, maxTokens = maxTokens).content.trim()
    } catch (_: Exception) { null }

    fun titleSessionAsync(session: Session) {
        val firstUser = session.messages.firstOrNull { it.role == "user" }?.content ?: return
        val firstReply = session.messages.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content.orEmpty()
        scope.launch(Dispatchers.IO) {
            val t = auxComplete(
                "Write a 3-6 word title for this conversation. Reply with the title only, no quotes.\n\nUser: ${firstUser.take(600)}\nAssistant: ${firstReply.take(400)}",
                maxTokens = 30,
            )?.lineSequence()?.firstOrNull()?.trim('"', '\'', ' ', '#', '*')?.take(70)
            if (!t.isNullOrBlank() && session.title.isBlank()) { session.title = t; sessions.save(session) }
        }
    }

    // ---------------------------------------------------------- service / notifications

    fun ensureServiceForCron() {
        if (cron.all().any { it.enabled }) HermesService.start(app)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Hermes gateway", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Hermes messages", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private var notifId = 1000

    /** Notification only when the user isn't looking at the app (e.g. after a browser sign-in). */
    fun notifyIfBackground(title: String, text: String) {
        if (!com.ersaz.hermesmobile.mcp.AppForeground.inForeground) notify(title, text)
    }

    fun notify(title: String, text: String) {
        val intent = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(3000)))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(app).notify(notifId++, n) } catch (_: SecurityException) {}
    }

    companion object {
        const val CH_SERVICE = "hermes_service"
        const val CH_EVENTS = "hermes_events"
        val VERSION = "${BuildConfig.VERSION_NAME} (Hermes Agent for Android)"

        @Volatile private var instance: HermesRuntime? = null
        fun get(context: Context): HermesRuntime =
            instance ?: synchronized(this) {
                instance ?: HermesRuntime(context.applicationContext as Application).also { instance = it }
            }
    }
}
