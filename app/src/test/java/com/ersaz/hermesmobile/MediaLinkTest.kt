package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.agent.tools.AnalyzeLinkTool
import org.junit.Assert.*
import org.junit.Test
class MediaLinkTest {
 @Test fun onlyBarePublicLinksTriggerAutomaticExtraction() {
  assertTrue(AnalyzeLinkTool.isBareMediaLink("https://youtu.be/abcdefghijk"))
  assertFalse(AnalyzeLinkTool.isBareMediaLink("Explain https://youtu.be/abcdefghijk"))
  assertFalse(AnalyzeLinkTool.isBareMediaLink("https://youtube.com.evil.test/watch?v=abcdefghijk"))
  assertFalse(AnalyzeLinkTool.isBareMediaLink("https://token@youtube.com/watch?v=abcdefghijk"))
  assertFalse(AnalyzeLinkTool.isBareMediaLink("http://youtube.com/watch?v=abcdefghijk"))
 }
}
