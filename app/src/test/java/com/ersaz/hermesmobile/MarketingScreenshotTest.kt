package com.ersaz.hermesmobile

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.ersaz.hermesmobile.ui.SettingsHub
import com.ersaz.hermesmobile.ui.theme.HermesTheme
import com.ersaz.hermesmobile.ui.theme.Skins
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual release composables rendered by Robolectric; no invented UI or live-account data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MarketingScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String, skinName: String) {
        val skin = Skins.all.first { it.name == skinName }
        compose.setContent {
            HermesTheme(skin) {
                Surface(Modifier.fillMaxSize(), color = skin.bg) { SettingsHub({}, {}) }
            }
        }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = File("build/marketing-screenshots/$name.png")
        target.parentFile.mkdirs()
        target.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(target.length() > 1000)
    }
    @Test fun settingsDark() = capture("hermes-settings-dark", "nous")
    @Test fun settingsLight() = capture("hermes-settings-light", "nous-light")
}
