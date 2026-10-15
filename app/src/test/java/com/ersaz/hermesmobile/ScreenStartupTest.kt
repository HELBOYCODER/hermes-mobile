package com.ersaz.hermesmobile

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.ersaz.hermesmobile.coding.CodingDefaults
import com.ersaz.hermesmobile.ui.McpScreen
import com.ersaz.hermesmobile.ui.SettingsHub
import com.ersaz.hermesmobile.ui.theme.HermesTheme
import com.ersaz.hermesmobile.ui.theme.Skins
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenStartupTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsHubOpens() {
        compose.setContent { HermesTheme(Skins.all.first()) { SettingsHub({}, {}) } }
        compose.onNodeWithText("Make Hermes yours").assertIsDisplayed()
    }
    @Test fun mcpPageOpensWithBundledServers() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.applicationInfo.nativeLibraryDir = application.filesDir.absolutePath
        val rt = HermesRuntime(application)
        (listOf(CodingDefaults.server().copy(enabled = false)) + CodingDefaults.optionalServers()).forEach { rt.mcp.store.upsert(it) }
        compose.setContent { HermesTheme(Skins.all.first()) { McpScreen(rt) } }
        compose.onNodeWithText("Coding integrations").assertIsDisplayed()
    }
}
