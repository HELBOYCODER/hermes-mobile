package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.coding.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class WorkspaceCheckpointTest {
 @Test fun restorePreservesUnrelatedChangesAndRejectsLaterUserEdit() {
  val root=Files.createTempDirectory("checkpoint").toFile()
  try {
   val f=java.io.File(root,"code.txt").apply {writeText("before")}
   val other=java.io.File(root,"user.txt").apply {writeText("dirty user work")}
   val cp=WorkspaceCheckpoint(root)
   val id=cp.capture(listOf("code.txt"))
   f.writeText("agent change");cp.seal(id)
   f.writeText("later user edit")
   assertTrue(cp.restore(id) is RestoreResult.Conflict)
   assertEquals("later user edit",f.readText());assertEquals("dirty user work",other.readText())
   f.writeText("agent change")
   assertTrue(cp.restore(id) is RestoreResult.Restored)
   assertEquals("before",f.readText());assertEquals("dirty user work",other.readText())
  } finally {root.deleteRecursively()}
 }
}
