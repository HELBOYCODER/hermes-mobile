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
}
