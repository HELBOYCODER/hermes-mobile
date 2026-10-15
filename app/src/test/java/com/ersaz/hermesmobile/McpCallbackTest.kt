package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.mcp.LoopbackCallback
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
class McpCallbackTest {
 @Test fun invalidStateCannotConsumeRealCallback()=runBlocking {
  val cb=LoopbackCallback()
  try {
   val result=async(Dispatchers.IO) { cb.await("expected") }
   fun call(state:String):Int {
    val c=URL(cb.redirectUri+"?state=$state&code=ok").openConnection() as HttpURLConnection
    return try { c.responseCode } finally {c.disconnect()}
   }
   assertEquals(400,withContext(Dispatchers.IO) {call("wrong")})
   assertEquals(200,withContext(Dispatchers.IO) {call("expected")})
   assertEquals("ok",result.await()["code"])
  } finally {cb.close()}
 }
}
