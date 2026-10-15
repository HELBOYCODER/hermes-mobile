package com.ersaz.hermesmobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.ersaz.hermesmobile.ui.ChatScreen
import com.ersaz.hermesmobile.ui.ChatViewModel
import com.ersaz.hermesmobile.ui.ChatItem
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String, skinName: String) {
        val skin = Skins.all.first { it.name == skinName }
        compose.setContent {
            HermesTheme(skin) {
                Surface(Modifier.fillMaxSize(), color = skin.bg) { SettingsHub({}, {}) }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(image)) }
        val target = File("build/marketing-screenshots/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(target.length() > 1000)
    }
    @Test fun chatPersianDemo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.applicationInfo.nativeLibraryDir = app.filesDir.absolutePath
        val vm = ChatViewModel(app)
        vm.items.clear()
        vm.items.add(ChatItem.User("demo-user", "برای پروژهٔ من یک برنامهٔ پیاده‌سازی بنویس؛ هدف، مراحل کار و معیار پذیرش را مشخص کن.", emptyList()))
        vm.items.add(ChatItem.Assistant("demo-assistant", "## از ایده تا اجرا\n\nابتدا هدف و محدودیت‌های پروژه را ثبت می‌کنیم. سپس مراحل کار را به وظایف کوچک و قابل بررسی تقسیم می‌کنیم.\n\n### مسیر پیشنهادی\n\n1. مشخص‌کردن نیازها و معیار پذیرش\n2. نوشتن طرح و وظایف\n3. پیاده‌سازی و بررسی تغییرات\n4. اجرای تست و ثبت نتیجه\n\nبرای شروع، زبان برنامه‌نویسی و خروجی موردنظرت را بگو."))
        compose.setContent { HermesTheme(Skins.all.first()) { Surface(Modifier.fillMaxSize(), color = Skins.all.first().bg) { ChatScreen(vm) } } }
        compose.waitForIdle()
        val target = File("build/marketing-screenshots/hermes-chat-persian-demo.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { check(run {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }) }
    }
    @Test fun settingsDark() = capture("hermes-settings-dark", "nous")
    @Test fun settingsLight() = capture("hermes-settings-light", "nous-light")
}
