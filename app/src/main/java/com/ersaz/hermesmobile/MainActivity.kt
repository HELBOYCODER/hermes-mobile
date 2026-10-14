package com.ersaz.hermesmobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.BotsScreen
import com.ersaz.hermesmobile.ui.BrowserScreen
import com.ersaz.hermesmobile.ui.ChatScreen
import com.ersaz.hermesmobile.ui.ChatViewModel
import com.ersaz.hermesmobile.ui.CronScreen
import com.ersaz.hermesmobile.ui.GatewayScreen
import com.ersaz.hermesmobile.ui.McpScreen
import com.ersaz.hermesmobile.ui.MemoryScreen
import com.ersaz.hermesmobile.ui.OnboardingScreen
import com.ersaz.hermesmobile.ui.SessionsScreen
import com.ersaz.hermesmobile.ui.SettingsScreen
import com.ersaz.hermesmobile.ui.SkillsScreen
import com.ersaz.hermesmobile.ui.SubAgentsScreen
import com.ersaz.hermesmobile.ui.TerminalScreen
import com.ersaz.hermesmobile.ui.UsageScreen
import com.ersaz.hermesmobile.ui.FilesScreen
import com.ersaz.hermesmobile.ui.LogsScreen
import com.ersaz.hermesmobile.ui.WebUiScreen
import com.ersaz.hermesmobile.ui.imageDataUri
import com.ersaz.hermesmobile.ui.importAttachment
import com.ersaz.hermesmobile.ui.theme.HermesTheme
import com.ersaz.hermesmobile.ui.theme.Skins
import kotlinx.coroutines.launch

enum class Dest(val title: String, val icon: ImageVector) {
    Chat("Chat", Icons.AutoMirrored.Filled.Chat),
    Bots("Bot mode", Icons.Default.SmartToy),
    Terminal("Terminal", Icons.Default.Terminal),
    Sessions("Sessions", Icons.Default.History),
    Skills("Skills", Icons.Default.AutoStories),
    Memory("Memory", Icons.Default.Psychology),
    Cron("Scheduled tasks", Icons.Default.Schedule),
    Browser("Browser", Icons.Default.Public),
    SubAgents("Sub-agents", Icons.Default.AccountTree),
    Mcp("MCP servers", Icons.Default.Extension),
    Gateway("Messaging gateway", Icons.Default.Hub),
    WebUi("Remote WebUI", Icons.Default.Language),
    Files("Files", Icons.Default.Folder),
    Usage("Usage stats", Icons.Default.BarChart),
    Logs("Logs", Icons.Default.BugReport),
    Settings("Settings", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { HermesRuntime.get(this).permissions.onResult(it) }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rt = HermesRuntime.get(this)
        rt.permissions.launcher = permLauncher
        // Park browser tabs in a 1px, almost transparent host so they keep rendering in the background.
        rt.browser.attachHost(android.widget.FrameLayout(this).also {
            it.alpha = 0.01f
            (window.decorView as android.view.ViewGroup).addView(it, android.view.ViewGroup.LayoutParams(1, 1))
        })
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)

        setContent {
            val version by rt.settings.version.collectAsState()
            val skin = remember(version) { Skins.byName(rt.settings.skin) }
            val fs = remember(version) { rt.settings.fontScaleValue() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * fs),
            ) { HermesTheme(skin) {
                var dest by rememberSaveable { mutableStateOf(Dest.Chat) }
                var onboarded by rememberSaveable { mutableStateOf(rt.settings.onboarded || rt.settings.apiKey.isNotBlank() || rt.nousAuth.signedIn) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                // The agent asked for the browser on screen (login / CAPTCHA hand-over).
                val showReq by rt.browser.showRequests.collectAsState()
                androidx.compose.runtime.LaunchedEffect(showReq) { if (showReq > 0) dest = Dest.Browser }
                val scope = rememberCoroutineScope()

                if (!onboarded) {
                    OnboardingScreen(rt) { rt.settings.onboarded = true; onboarded = true }
                    return@HermesTheme
                }

                val navColors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent, selectedContainerColor = skin.surface,
                    selectedTextColor = skin.title, unselectedTextColor = skin.label,
                    selectedIconColor = skin.accent, unselectedIconColor = skin.dim,
                )
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = skin.bg, drawerContentColor = skin.text) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                Row(Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(skin.symbol, color = skin.accent, fontSize = 26.sp)
                                    Text(skin.agentName, color = skin.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                                        modifier = Modifier.padding(start = 10.dp))
                                }
                                NavigationDrawerItem(
                                    label = { Text("New chat", fontWeight = FontWeight.Medium) },
                                    icon = { Icon(Icons.Default.AddComment, null) }, selected = false,
                                    onClick = { vm.newSession(); dest = Dest.Chat; scope.launch { drawer.close() } },
                                    colors = NavigationDrawerItemDefaults.colors(
                                        unselectedContainerColor = skin.surface, unselectedTextColor = skin.title, unselectedIconColor = skin.accent),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Dest.entries.forEach { d ->
                                    NavigationDrawerItem(
                                        label = { Text(d.title) }, icon = { Icon(d.icon, null) }, selected = d == dest,
                                        onClick = { dest = d; scope.launch { drawer.close() } },
                                        colors = navColors, shape = RoundedCornerShape(12.dp),
                                    )
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    },
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize().background(skin.bg),
                        containerColor = skin.bg,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(if (dest == Dest.Chat) (vm.activeBot?.let { "${it.name} · " }.orEmpty() + vm.sessionTitle.ifBlank { skin.agentName }) else dest.title,
                                        color = skin.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                },
                                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "menu", tint = skin.label) } },
                                actions = {
                                    if (dest == Dest.Chat) IconButton(onClick = { vm.newSession() }) { Icon(Icons.Default.AddComment, "new chat", tint = skin.label) }
                                    if (dest != Dest.Terminal) IconButton(onClick = { dest = Dest.Terminal }) { Icon(Icons.Default.Terminal, "terminal", tint = skin.label) }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = skin.bg, scrolledContainerColor = skin.bg),
                            )
                        },
                    ) { pad ->
                        Column(Modifier.padding(pad).fillMaxSize()) {
                            when (dest) {
                                Dest.Chat -> ChatScreen(vm)
                                Dest.Bots -> BotsScreen(rt, vm) { dest = Dest.Chat }
                                Dest.Terminal -> TerminalScreen(rt)
                                Dest.Sessions -> SessionsScreen(rt, vm) { dest = Dest.Chat }
                                Dest.Skills -> SkillsScreen(rt, vm) { dest = Dest.Chat }
                                Dest.Memory -> MemoryScreen(rt)
                                Dest.Cron -> CronScreen(rt)
                                Dest.Browser -> BrowserScreen(rt)
                                Dest.SubAgents -> SubAgentsScreen(rt)
                                Dest.Mcp -> McpScreen(rt)
                                Dest.Gateway -> GatewayScreen(rt)
                                Dest.WebUi -> WebUiScreen(rt)
                                Dest.Files -> FilesScreen(rt)
                                Dest.Usage -> UsageScreen(rt)
                                Dest.Logs -> LogsScreen(rt)
                                Dest.Settings -> SettingsScreen(rt)
                            }
                        }
                    }
                }
            } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share to Hermes" from other apps: text prefills the box, images go to vision, every other file is attached. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.input = it }
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        if (uris.isEmpty()) return
        Thread {
            uris.forEach { uri ->
                val att = importAttachment(this, uri) ?: return@forEach
                val img = if (att.isImage) imageDataUri(this, uri) else null
                runOnUiThread { vm.pendingFiles += att; if (img != null) vm.pendingImages += img }
            }
        }.start()
    }
}

@Composable
private fun <T> remember(key: Any, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
