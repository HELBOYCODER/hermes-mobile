package com.ersaz.hermesmobile.bots

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.Agent
import com.ersaz.hermesmobile.agent.AgentListener
import com.ersaz.hermesmobile.agent.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One roster row: who, and what they said last. */
data class RosterEntry(
    val bot: Bot,
    val preview: String,
    val previewRole: String,
    val updatedAt: Long,
    val sessionTitle: String,
    val running: Boolean,
    val activeRecently: Boolean,
)

/**
 * Runtime side of Bot Mode: which bots are working right now, roster previews, per-bot sessions,
 * bot-to-bot delivery and group rooms.
 */
class BotHub(private val rt: HermesRuntime) {
    private val running = ConcurrentHashMap<String, Int>()
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    /** Bots with a turn in flight right now (drives the "Active now" strip and the avatar's scanning eyes). */
    val runningIds: StateFlow<Set<String>> = _runningIds

    private val inboxLocks = ConcurrentHashMap<String, Mutex>()
    private val rooms = ConcurrentHashMap<String, GroupRoom>()

    fun setRunning(botId: String, on: Boolean) {
        running.compute(botId) { _, n -> ((n ?: 0) + if (on) 1 else -1).coerceAtLeast(0) }
        _runningIds.value = running.filterValues { it > 0 }.keys.toSet()
    }

    // ------------------------------------------------------------------ sessions

    private fun ownsChat(botId: String, s: com.ersaz.hermesmobile.agent.SessionSummary) =
        if (botId == Bot.DEFAULT_ID) s.source == "app" || s.botId == botId else s.botId == botId

    /** The bot's primary conversation: its latest non-inbox chat, or a fresh one. */
    fun mainSessionFor(bot: Bot): Session {
        val latest = rt.sessions.list().firstOrNull { ownsChat(bot.id, it) && it.chatKey == null && it.source != "cron" }
        latest?.let { rt.sessions.load(it.id) }?.let { return it }
        return newChatSession(bot)
    }

    fun newChatSession(bot: Bot): Session =
        if (bot.isDefault) Session(source = "app")
        else Session(source = "bot", botId = bot.id)

    /** Every conversation that belongs to [bot] (chats, bot-to-bot inbox, group rooms, routine runs). */
    fun sessionsOf(bot: Bot) = rt.sessions.list().filter { ownsChat(bot.id, it) }

    private fun specialSession(bot: Bot, chatKey: String, title: String): Session {
        val hit = rt.sessions.list().firstOrNull { it.botId == bot.id && it.chatKey == chatKey }
        hit?.let { rt.sessions.load(it.id) }?.let { return it }
        return Session(source = "bot", botId = bot.id, chatKey = chatKey, title = title).also { rt.sessions.save(it) }
    }

    fun inboxSessionFor(bot: Bot) = specialSession(bot, "bot:${bot.id}:inbox", "Bot Chat")
    fun groupSessionFor(bot: Bot, group: String) = specialSession(bot, "bot:${bot.id}:group:${Bot.slug(group)}", "Group: $group")

    fun agentFor(bot: Bot, session: Session, platform: String = "bot", toolsets: Set<String>? = null): Agent =
        Agent(rt, session, platform, toolsets, 0, bot.takeUnless { it.isDefault })

    // ------------------------------------------------------------------ roster

    fun roster(): List<RosterEntry> {
        val summaries = rt.sessions.list()
        val runningNow = _runningIds.value
        val now = System.currentTimeMillis()
        return rt.bots.all().map { bot ->
            val mine = summaries.filter { ownsChat(bot.id, it) && it.source != "cron" }
            val last = mine.firstOrNull { it.preview.isNotBlank() }
            RosterEntry(
                bot = bot,
                preview = last?.preview.orEmpty(), previewRole = last?.previewRole.orEmpty(),
                updatedAt = last?.updatedAt ?: 0, sessionTitle = last?.title.orEmpty(),
                running = bot.id in runningNow,
                activeRecently = bot.id in runningNow || (last != null && now - last.updatedAt < ACTIVE_WINDOW_MS && last.previewRole == "assistant"),
            )
        }
    }

    /** Prompt block that teaches an agent about its roster and the @mention hand-off protocol. */
    fun rosterPrompt(agent: Agent): String? {
        val others = rt.bots.all().filter { it.id != agent.botId }
        if (others.isEmpty()) return null
        return buildString {
            append("## Bot roster\nYou are part of a roster of Hermes bots. When the user writes \"@name …\" or a task clearly belongs to another bot, ")
            append("hand it off with the message_bot tool: it delivers \"Message from 🤖 <you> (@<you>): …\" to that bot's Bot Chat, waits for its reply and returns it — then report back to the user. ")
            append("Messages you receive that start with \"Message from 🤖\" come from another bot; answer them directly and concisely.\n")
            others.forEach { b ->
                append("- @${b.id} — ${b.name}")
                if (b.title.isNotBlank()) append(", ${b.title}")
                if (b.description.isNotBlank()) append(": ${b.description.take(160)}")
                append('\n')
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------ bot-to-bot

    /** Deliver [text] from [from] (null = the user's default agent acting alone) to [to]; returns the reply. */
    suspend fun deliver(from: Bot?, to: Bot, text: String, chain: List<String>): Result<String> {
        if (chain.size >= MAX_HOPS) return Result.failure(IllegalStateException("Hand-off chain too deep (max $MAX_HOPS bots)."))
        if (to.id in chain) return Result.failure(IllegalStateException("@${to.id} is already part of this hand-off chain."))
        val sender = from ?: rt.bots.get(Bot.DEFAULT_ID)!!
        val lock = inboxLocks.getOrPut(to.id) { Mutex() }
        return lock.withLock {
            try {
                val agent = agentFor(to, inboxSessionFor(to)).also { it.botChain = chain + to.id }
                val reply = agent.runTurn(BotText.a2aPrefix(sender.name, sender.id) + text, emptyList(), object : AgentListener {})
                Result.success(reply.ifBlank { "(no reply)" })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ------------------------------------------------------------------ groups

    fun room(group: String): GroupRoom = rooms.getOrPut(group.lowercase()) { GroupRoom(rt, group) }

    fun forgetRoom(group: String) { rooms.remove(group.lowercase())?.stop() }

    companion object {
        const val ACTIVE_WINDOW_MS = 90_000L
        const val MAX_HOPS = 3
    }
}
