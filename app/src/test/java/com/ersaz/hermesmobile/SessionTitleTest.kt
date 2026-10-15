package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.agent.Session
import org.junit.Assert.*
import org.junit.Test
class SessionTitleTest {
 @Test fun generatedTitleObjectIsReadableWithoutChangingStoredTitle() {
  val raw="""{"title":"رفع خطای ورود Hermes"}"""
  val s=Session(title=raw)
  assertEquals("رفع خطای ورود Hermes",s.displayTitle())
  assertEquals(raw,s.title)
  assertEquals("My {custom} title",Session(title="My {custom} title").displayTitle())
 }
}
