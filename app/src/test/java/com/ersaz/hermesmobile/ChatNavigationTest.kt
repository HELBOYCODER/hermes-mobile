package com.ersaz.hermesmobile
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ersaz.hermesmobile.ui.*
import com.ersaz.hermesmobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ChatNavigationTest {
 @get:Rule val compose=createComposeRule()
 @Test fun chatToolsMenuHasRealActions() {
  var selected: SessionAction?=null
  compose.setContent { HermesTheme(Skins.all.first()) { ChatSessionMenu(onAction={selected=it}) } }
  compose.onNodeWithText("Chat tools").performClick()
  compose.onNodeWithText("Browse Chat Files").performClick()
  assertEquals(SessionAction.Files,selected)
 }
}
