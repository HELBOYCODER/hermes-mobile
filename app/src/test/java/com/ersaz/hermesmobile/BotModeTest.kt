package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.agent.MemoryStore
import com.ersaz.hermesmobile.agent.Session
import com.ersaz.hermesmobile.bots.Bot
import com.ersaz.hermesmobile.bots.BotStore
import com.ersaz.hermesmobile.bots.BotText
import com.ersaz.hermesmobile.cron.CronJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BotModeTest {
    private fun store(): Pair<BotStore, MemoryStore> {
        val root = Files.createTempDirectory("bots").toFile()
        val shared = MemoryStore(java.io.File(root, "memories"))
        return BotStore(java.io.File(root, "bots"), shared) to shared
    }

    @Test
    fun rosterAlwaysStartsWithTheUndeletableDefault() {
        val (bots, _) = store()
        assertEquals(listOf("default"), bots.all().map { it.id })
        val a = bots.create("Researcher", "Analyst", "Digs into things")
        val b = bots.create("Researcher")
        assertEquals("researcher", a.id)
        assertEquals("researcher-2", b.id)
        assertEquals(listOf("default", "researcher", "researcher-2"), bots.all().map { it.id })
        assertFalse(bots.delete("default"))
        assertTrue(bots.delete("researcher-2"))
        assertEquals(2, bots.all().size)
    }

    @Test
    fun lookupByIdNameAtMentionAndSlug() {
        val (bots, _) = store()
        val b = bots.create("Song Writer")
        assertEquals(b.id, bots.get("@song-writer")?.id)
        assertEquals(b.id, bots.get("Song Writer")?.id)
        assertEquals(b.id, bots.get("song writer")?.id)
        assertNull(bots.get("nobody"))
        assertEquals("default", bots.get("hermes")?.id)
    }

    @Test
    fun eachBotHasPrivateMemoryAndDefaultSharesTheMainOne() {
        val (bots, shared) = store()
        val a = bots.create("A"); val b = bots.create("B")
        bots.memoryFor(a).apply("memory", listOf(JSONObject().put("action", "add").put("content", "a-secret")))
        assertEquals(listOf("a-secret"), bots.memoryFor(a).entries("memory"))
        assertTrue(bots.memoryFor(b).entries("memory").isEmpty())
        assertTrue(shared.entries("memory").isEmpty())
        assertTrue(bots.memoryFor(null) === shared)
        assertTrue(bots.memoryFor(bots.get("default")) === shared)
    }

    @Test
    fun duplicateClonesConfigAndMemory() {
        val (bots, _) = store()
        val a = bots.create("Coder", "Dev", "Writes code", soul = "Be terse.", model = "m/x")
        a.disabledToolsets = listOf("web"); a.group = "Team"; bots.save(a)
        bots.memoryFor(a).apply("user", listOf(JSONObject().put("action", "add").put("content", "likes kotlin")))
        val copy = bots.duplicate(a.id)!!
        assertNotEquals(a.id, copy.id)
        assertEquals("Be terse.", copy.soul); assertEquals("m/x", copy.model)
        assertEquals(listOf("web"), copy.disabledToolsets); assertEquals("Team", copy.group)
        assertEquals(listOf("likes kotlin"), bots.memoryFor(copy).entries("user"))
        bots.memoryFor(copy).apply("user", listOf(JSONObject().put("action", "add").put("content", "extra")))
        assertEquals(1, bots.memoryFor(a).entries("user").size)
    }

    @Test
    fun groupsAreLabeledAndSortedIgnoringCase() {
        val (bots, _) = store()
        bots.create("A").also { it.group = "zeta"; bots.save(it) }
        bots.create("B").also { it.group = "Alpha"; bots.save(it) }
        bots.create("C").also { it.group = "alpha"; bots.save(it) }
        bots.create("D")
        val g = bots.groups()
        assertEquals(listOf("Alpha", "zeta"), g.keys.toList())
        assertEquals("labels differing only in case are one group", 2, g.getValue("Alpha").size)
    }

    @Test
    fun mentionsResolveByIdOrNameInOrderWithoutDuplicates() {
        val (bots, _) = store()
        val r = bots.create("Researcher"); val c = bots.create("Code Monkey")
        val all = bots.all()
        assertEquals(listOf("code-monkey", "researcher"), BotText.mentions("@code-monkey and @Researcher please, @researcher again, mail me@researcher.io", all).map { it.id })
        assertEquals(listOf("code-monkey"), BotText.mentions("hey @code-monkey", all).map { it.id })
        assertTrue(BotText.mentions("no mentions", all).isEmpty())
        assertTrue(BotText.mentionsUser("this needs your call, @user"))
        assertFalse(BotText.mentionsUser("@username is fine"))
        assertTrue(r.id.isNotEmpty() && c.id.isNotEmpty())
    }

    @Test
    fun passDetection() {
        listOf("PASS", "pass", " Pass. ", "**PASS**", "", "(pass)").forEach { assertTrue("'$it'", BotText.isPass(it)) }
        listOf("Passing thought: ship it", "I'd pass on that idea, here's why…").forEach { assertFalse("'$it'", BotText.isPass(it)) }
        assertEquals("Message from 🤖 Hermes (@default): ", BotText.a2aPrefix("Hermes", "default"))
    }

    @Test
    fun botIdSurvivesSessionAndCronJsonRoundTrips() {
        val s = Session(source = "bot", botId = "researcher", chatKey = "bot:researcher:inbox")
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals("researcher", back.botId)
        assertEquals("bot:researcher:inbox", back.chatKey)
        assertNull(Session.fromJson(JSONObject(Session().toJson().toString())).botId)

        val job = CronJob(name = "digest", prompt = "p", schedule = "every 1d", botId = "researcher")
        assertEquals("researcher", CronJob.fromJson(JSONObject(job.toJson().toString())).botId)
        assertNull(CronJob.fromJson(JSONObject(CronJob(name = "n", prompt = "p", schedule = "30m").toJson().toString())).botId)
    }

    @Test
    fun botJsonRoundTripClampsAvatarIndexes() {
        val b = Bot("x", "X", shape = 3, color = 9, disabledToolsets = listOf("web", "terminal"), group = "G")
        val back = Bot.fromJson(JSONObject(b.toJson().put("shape", 99).put("color", -1).toString()))
        assertEquals(99 % Bot.SHAPES, back.shape)
        assertTrue(back.color in 0 until Bot.COLORS)
        assertEquals(listOf("web", "terminal"), back.disabledToolsets)
    }
}
