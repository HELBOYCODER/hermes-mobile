package com.ersaz.hermesmobile

import android.app.Application
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryActivityTest {
    @Test fun safeRecoveryPreservesDataAndDisablesAutomaticStartup() {
        val activity = Robolectric.buildActivity(RecoveryActivity::class.java).setup().get()
        val marker = java.io.File(activity.filesDir, "preserve-session.txt").apply { writeText("saved chat") }
        val content = activity.findViewById<android.widget.FrameLayout>(android.R.id.content)
        val layout = content.getChildAt(0) as LinearLayout
        (layout.getChildAt(1) as Button).performClick()
        assertTrue(activity.getSharedPreferences("recovery", 0).getBoolean("safe", false))
        assertFalse(activity.getSharedPreferences("recovery", 0).getBoolean("crashed", true))
        assertEquals("saved chat", marker.readText())
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        marker.delete()
    }
}
