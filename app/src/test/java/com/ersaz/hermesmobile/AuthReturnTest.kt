package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.auth.AuthOperationStore
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class AuthReturnTest {
 @Test fun expiredAndDuplicateReturnsCannotComplete() {
  val dir=Files.createTempDirectory("auth-ops").toFile()
  try {
   val store=AuthOperationStore(java.io.File(dir,"ops.json"))
   val id=store.create("mcp",100)
   assertFalse(store.consume("wrong",1))
   assertTrue(store.consume(id,99))
   assertFalse(store.consume(id,99))
   val expired=store.create("nous",100)
   assertFalse(store.consume(expired,100))
  } finally { dir.deleteRecursively() }
 }
 @Test fun concurrentTicketsSurviveSeparateStoreInstances() {
  val dir=Files.createTempDirectory("auth-concurrent").toFile()
  try {
   val file=java.io.File(dir,"ops.json")
   val ids=java.util.Collections.synchronizedList(mutableListOf<String>())
   val threads=(1..16).map { Thread { ids.add(AuthOperationStore(file).create("mcp",100)) } }
   threads.forEach { it.start() };threads.forEach { it.join() }
   assertEquals(16,ids.size)
   assertTrue(ids.all { AuthOperationStore(file).consume(it,1) })
  } finally {dir.deleteRecursively()}
 }
}
