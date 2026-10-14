package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.jobs.JobState
import com.ersaz.hermesmobile.agent.jobs.SubAgentDef
import com.ersaz.hermesmobile.agent.jobs.SubAgentLimits
import com.ersaz.hermesmobile.agent.jobs.SubAgentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SubAgentTest {
    private fun store() = SubAgentStore(Files.createTempDirectory("sa").resolve("subagents.json").toFile())

    @Test
    fun rosterAlwaysStartsWithTheGeneralAgentAndResolvesByName() {
        val s = store()
        assertEquals(listOf("general"), s.all().map { it.name })
        s.upsert(SubAgentDef(name = "Researcher", description = "Digs through the web"))
        assertEquals("Researcher", s.resolve("researcher").name)
        assertEquals("general", s.resolve("nobody").name)
        assertEquals("general", s.resolve(null).name)
    }

    @Test
    fun limitsAreClampedOnSaveAndTheBuiltInCannotBeRemoved() {
        val s = store()
        val d = SubAgentDef(name = "n".repeat(100), description = "d".repeat(500), instructions = "i".repeat(9000))
        s.upsert(d)
        val back = s.all().last()
        assertEquals(SubAgentLimits.NAME_MAX, back.name.length)
        assertEquals(SubAgentLimits.DESCRIPTION_MAX, back.description.length)
        assertEquals(SubAgentLimits.INSTRUCTIONS_MAX, back.instructions.length)
        s.remove("builtin")
        assertTrue(s.all().first().builtIn)
        repeat(20) { s.upsert(SubAgentDef(name = "a$it", description = "x")) }
        assertEquals(SubAgentLimits.MAX_COUNT, s.all().size)
    }

    @Test
    fun finishedStatesAreTerminal() {
        listOf(JobState.DONE, JobState.CANCELLED, JobState.FAILED, JobState.TIMEOUT).forEach { assertTrue(it.finished) }
        listOf(JobState.QUEUED, JobState.RUNNING, JobState.INTERRUPTED).forEach { assertTrue(!it.finished) }
    }
}
