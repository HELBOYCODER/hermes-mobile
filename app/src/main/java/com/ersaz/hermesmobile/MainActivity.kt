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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.BotsScreen
import com.ersaz.hermesmobile.ui.ChatScreen
import com.ersaz.hermesmobile.ui.ChatViewModel
import com.ersaz.hermesmobile.ui.CronScreen
import com.ersaz.hermesmobile.ui.GatewayScreen
import com.ersaz.hermesmobile.ui.MemoryScreen
import com.ersaz.hermesmobile.ui.OnboardingScreen
import com.ersaz.hermesmobile.ui.SessionsScreen
import com.ersaz.hermesmobile.ui.SettingsScreen
import com.ersaz.hermesmobile.ui.SkillsScreen
import com.ersaz.hermesmobile.ui.TerminalScreen
import com.ersaz.hermesmobile.ui.WebUiScreen
import com.ersaz.hermesmobile.ui.imageDataUri
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
    Gateway("Messaging gateway", Icons.Default.Hub),
    WebUi("Remote WebUI", Icons.Default.Language),
    Settings("Settings", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rt = HermesRuntime.get(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)

        setContent {
            val version by rt.settings.version.collectAsState()
            val skin = remember(version) { Skins.byName(rt.settings.skin) }
            HermesTheme(skin) {
                var dest by rememberSaveable { mutableStateOf(Dest.Chat) }
                var onboarded by rememberSaveable { mutableStateOf(rt.settings.onboarded || rt.settings.apiKey.isNotBlank() || rt.nousAuth.signedIn) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                if (!onboarded) {
                    OnboardingScreen(rt) { rt.settings.onboarded = true; onboarded = true }
                    return@HermesTheme
                }

                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = skin.surface) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("${skin.symbol} ${skin.agentName}", color = skin.title, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                    modifier = Modifier.padding(20.dp))
                                NavigationDrawerItem(
                                    label = { Text("New session") }, icon = { Icon(Icons.Default.AddComment, null) }, selected = false,
                                    onClick = { vm.newSession(); dest = Dest.Chat; scope.launch { drawer.close() } },
                                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = skin.surface),
                                )
                                Dest.entries.forEach { d ->
                                    NavigationDrawerItem(
                                        label = { Text(d.title) }, icon = { Icon(d.icon, null) }, selected = d == dest,
                                        onClick = { dest = d; scope.launch { drawer.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = skin.surface, selectedContainerColor = skin.selection),
                                        modifier = Modifier.padding(horizontal = 8.dp),
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
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(if (dest == Dest.Chat) (vm.activeBot?.let { "${it.name} · " }.orEmpty() + vm.sessionTitle.ifBlank { skin.agentName }) else dest.title,
                                        color = skin.title, fontSize = 17.sp, maxLines = 1)
                                },
                                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "menu", tint = skin.accent) } },
                                actions = {
                                    if (dest == Dest.Chat) IconButton(onClick = { vm.newSession() }) { Icon(Icons.Default.AddComment, "new", tint = skin.accent) }
                                    if (dest != Dest.Terminal) IconButton(onClick = { dest = Dest.Terminal }) { Icon(Icons.Default.Terminal, "terminal", tint = skin.label) }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = skin.surface),
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
                                Dest.Gateway -> GatewayScreen(rt)
                                Dest.WebUi -> WebUiScreen(rt)
                                Dest.Settings -> SettingsScreen(rt)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share to Hermes" from other apps prefills the chat input. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.input = it }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri -> imageDataUri(this, uri)?.let { vm.pendingImages += it } }
    }
}

@Composable
private fun <T> remember(key: Any, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
