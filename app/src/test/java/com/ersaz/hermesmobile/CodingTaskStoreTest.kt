package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.coding.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class CodingTaskStoreTest {
 @Test fun completedTaskRequiresPassedRunAndPersistsWithoutReplay() {
  val root=Files.createTempDirectory("tasks").toFile()
  try {
   val st=CodingTaskStore(root)
   st.update("one","Fix login","doing")
   try { st.update("one","Fix login","done");fail("unverified completion") } catch (_:IllegalArgumentException) {}
   st.recordRun("run1",0,"FAILED")
   try {st.update("one","Fix login","done","run1");fail("failed run")}catch (_:IllegalArgumentException){}
   st.recordRun("run2",0,"PASSED")
   st.update("one","Fix login","done","run2")
   assertEquals("done",CodingTaskStore(root).read().getJSONArray("tasks").getJSONObject(0).getString("status"))
  } finally {root.deleteRecursively()}
 }
}
