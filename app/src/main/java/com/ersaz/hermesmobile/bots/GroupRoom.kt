package com.ersaz.hermesmobile.bots

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.core.objects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GroupMessage(val sender: String, val text: String, val ts: Long = System.currentTimeMillis())

/**
 * A shared room for a bot group (2–6 bots). A user message starts up to [MAX_ROUNDS] serial rounds:
 * @mentioned bots answer (everyone when nobody is mentioned), each replies briefly or passes, and the room
 * settles when a whole round stays silent. Hard caps keep it from spinning. Each bot keeps its own persistent
 * `Group: <name>` session so room context survives like any other conversation.
 */
class GroupRoom(private val rt: HermesRuntime, val name: String) {
    private val file = File(rt.hermesHome, "bots/groups/${Bot.slug(name)}.json")
    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<GroupMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _needsUser = MutableStateFlow(false)
    /** A bot used @user: the room is waiting on a human decision. */
    val needsUser: StateFlow<Boolean> = _needsUser
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking
    private var job: Job? = null
    /** How many room messages each bot has already been shown. */
    private val seen = mutableMapOf<String, Int>()

    fun members(): List<Bot> = rt.bots.all().filter { it.group.equals(name, true) }.take(Bot.MAX_GROUP_MEMBERS)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (job?.isActive == true) { job?.cancel() }
        job = rt.scope.launch(Dispatchers.IO) {
            _busy.value = true
            try { converse(t) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { append("system", "⚠️ ${e.message}") }
            finally { _busy.value = false; _speaking.value = null }
        }
    }

    fun stop() { job?.cancel() }

    fun clear() {
        stop()
        _messages.value = emptyList(); seen.clear(); _needsUser.value = false
        file.delete()
    }

    private suspend fun converse(userText: String) {
        val members = members()
        if (members.size < 2) { append("system", "A group needs at least 2 bots."); return }
        append("user", userText)
        _needsUser.value = false
        var queue = BotText.mentions(userText, members).ifEmpty { members }
        var produced = 0
        for (round in 0 until MAX_ROUNDS) {
            if (queue.isEmpty() || produced >= MAX_MESSAGES) break
            val pulledIn = mutableListOf<Bot>()
            var spoke = false
            for (bot in queue) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive || produced >= MAX_MESSAGES) break
                _speaking.value = bot.id
                val reply = ask(bot, members) ?: continue
                produced++; spoke = true
                append(bot.id, reply)
                if (BotText.mentionsUser(reply)) _needsUser.value = true
                BotText.mentions(reply, members).filter { it.id != bot.id && pulledIn.none { p -> p.id == it.id } }.forEach { pulledIn += it }
            }
            if (!spoke) break // a silent round settles the room
            queue = pulledIn.ifEmpty { members }
        }
    }

    private suspend fun ask(bot: Bot, members: List<Bot>): String? {
        val all = _messages.value
        val from = seen[bot.id] ?: 0
        val fresh = all.drop(from).filter { it.sender != bot.id }
        seen[bot.id] = all.size
        if (fresh.isEmpty()) return null
        val names = members.associate { it.id to it.name }
        val transcript = fresh.joinToString("\n") { m ->
            val who = when (m.sender) { "user" -> "User"; "system" -> "System"; else -> "${names[m.sender] ?: m.sender} (@${m.sender})" }
            "$who: ${m.text}"
        }
        val prompt = "[Group chat \"$name\" — members: ${members.joinToString(", ") { "${it.name} (@${it.id})" }}]\n" +
            "New messages since you last spoke:\n$transcript\n\n" +
            "Reply to the room in 1-4 short sentences, or reply exactly PASS if you have nothing to add. " +
            "Pull another member in with @id. Use @user only for a real judgment call that needs the human."
        val agent = rt.botHub.agentFor(bot, rt.botHub.groupSessionFor(bot, name), toolsets = ROOM_TOOLSETS)
        val reply = try {
            agent.runTurn(prompt, emptyList(), object : AgentListener {})
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { append("system", "⚠️ ${bot.name}: ${e.message}"); return null }
        return reply.takeUnless { BotText.isPass(it) }?.trim()
    }

    private fun append(sender: String, text: String) {
        _messages.value = (_messages.value + GroupMessage(sender, text)).takeLast(MAX_STORED)
        save()
    }

    private fun load(): List<GroupMessage> = try {
        if (!file.exists()) emptyList()
        else JSONObject(file.readText()).optJSONArray("messages")?.objects()
            ?.map { GroupMessage(it.optString("sender"), it.optString("text"), it.optLong("ts")) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("messages", JSONArray().also { a ->
            _messages.value.forEach { a.put(JSONObject().put("sender", it.sender).put("text", it.text).put("ts", it.ts)) }
        }).toString())
    }

    companion object {
        const val MAX_ROUNDS = 3
        const val MAX_MESSAGES = 10
        const val MAX_STORED = 300
        /** Room turns stay light: no terminal, no further hand-offs (those would recurse). */
        val ROOM_TOOLSETS = setOf("web", "memory", "skills", "session_search", "todo")
    }
}
