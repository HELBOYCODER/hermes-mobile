package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.sandbox.waitForCommand
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class ProcessWaitTest {
 @Test fun cancellingWaitStopsTheChild()=runBlocking {
  val process=ProcessBuilder("sleep", "30").start()
  try {
   val job=launch(Dispatchers.IO) {waitForCommand(process,60)}
   delay(100)
   withTimeout(2000) {job.cancelAndJoin()}
   assertTrue(process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))
  } finally {process.destroyForcibly()}
 }
}
