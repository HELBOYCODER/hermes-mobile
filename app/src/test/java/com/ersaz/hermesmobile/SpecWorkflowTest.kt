package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.coding.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class SpecWorkflowTest {
 @Test fun initializationAndAdvancementPreserveExistingProjectInstructions() {
  val root=Files.createTempDirectory("spec-project").toFile()
  try {
   java.io.File(root,"AGENTS.md").writeText("my instructions")
   val wf=SpecWorkflow()
   wf.initialize(root)
   val constitution=java.io.File(root,".specify/memory/constitution.md")
   constitution.writeText("custom rules")
   wf.initialize(root)
   assertEquals("custom rules",constitution.readText())
   assertEquals("my instructions",java.io.File(root,"AGENTS.md").readText())
   val spec=wf.advance(root,CodingPhase.SPECIFY)
   assertTrue(spec.isFile);assertTrue(spec.readText().contains("Acceptance"))
   spec.writeText("user edited")
   wf.advance(root,CodingPhase.SPECIFY)
   assertEquals("user edited",spec.readText())
  } finally {root.deleteRecursively()}
 }
}
