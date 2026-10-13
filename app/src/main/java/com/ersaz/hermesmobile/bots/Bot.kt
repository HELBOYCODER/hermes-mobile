package com.ersaz.hermesmobile.bots

import com.ersaz.hermesmobile.agent.MemoryStore
import com.ersaz.hermesmobile.core.objects
import com.ersaz.hermesmobile.core.optStringOrNull
import com.ersaz.hermesmobile.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A bot is a Hermes profile: its own identity (SOUL), memory, model pin, toolset limits and chat history.
 * Port of Nous' Hermes Bot Mode roster — `default` is the always-present main Hermes and cannot be deleted.
 */
data class Bot(
    val id: String,
    var name: String,
    var title: String = "",
    var description: String = "",
    var shape: Int = 0,          // avatar shape, 0 until SHAPES-1
    var color: Int = 0,          // avatar color, 0 until COLORS-1
    var soul: String = "",       // SOUL.md for non-default bots
    var model: String = "",      // "" = the app-wide model
    var disabledToolsets: List<String> = emptyList(),
    var group: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isDefault: Boolean get() = id == DEFAULT_ID

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("title", title)
        .put("description", description).put("shape", shape).put("color", color).put("soul", soul)
        .put("model", model).put("disabled_toolsets", JSONArray(disabledToolsets)).put("group", group)
        .put("created_at", createdAt)

    companion object {
        const val DEFAULT_ID = "default"
        const val SHAPES = 7
        const val COLORS = 10
        const val MAX_GROUP_MEMBERS = 6

        fun fromJson(o: JSONObject) = Bot(
            id = o.getString("id"), name = o.optString("name").ifBlank { o.getString("id") },
            title = o.optString("title"), description = o.optString("description"),
            shape = o.optInt("shape").mod(SHAPES), color = o.optInt("color").mod(COLORS),
            soul = o.optString("soul"), model = o.optString("model"),
            disabledToolsets = o.optJSONArray("disabled_toolsets")?.strings() ?: emptyList(),
            group = o.optString("group"), createdAt = o.optLong("created_at", System.currentTimeMillis()),
        )

        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "bot" }
    }
}

/**
 * Roster persistence: `$HERMES_HOME/bots/bots.json`, plus a private memory directory per bot
 * (`bots/<id>/memories`) so each bot remembers on its own.
 */
class BotStore(private val dir: File, private val defaultMemory: MemoryStore) {
    private val file = File(dir, "bots.json")
    private val memories = mutableMapOf<String, MemoryStore>()

    init { dir.mkdirs() }

    @Synchronized
    private fun read(): MutableList<Bot> = try {
        if (!file.exists()) mutableListOf()
        else JSONObject(file.readText()).optJSONArray("bots")?.objects()?.map { Bot.fromJson(it) }?.toMutableList() ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    @Synchronized
    private fun write(list: List<Bot>) {
        val tmp = File(dir, "bots.json.tmp")
        tmp.writeText(JSONObject().put("bots", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }).toString(2))
        tmp.renameTo(file)
    }

    /** Default first, then in creation order. */
    @Synchronized
    fun all(): List<Bot> {
        val stored = read()
        val default = stored.firstOrNull { it.isDefault } ?: Bot(Bot.DEFAULT_ID, "Hermes", "Main agent", "Your everyday Hermes.", createdAt = 0)
        return listOf(default) + stored.filter { !it.isDefault }.sortedBy { it.createdAt }
    }

    fun get(idOrName: String?): Bot? {
        val q = idOrName?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
        val list = all()
        return list.firstOrNull { it.id.equals(q, true) } ?: list.firstOrNull { it.name.equals(q, true) }
            ?: list.firstOrNull { Bot.slug(it.name) == Bot.slug(q) }
    }

    @Synchronized
    fun save(bot: Bot): Bot {
        val list = read()
        val i = list.indexOfFirst { it.id == bot.id }
        if (i >= 0) list[i] = bot else list += bot
        write(list)
        return bot
    }

    /** Create a bot with a unique id derived from [name]. */
    @Synchronized
    fun create(name: String, title: String = "", description: String = "", soul: String = "", model: String = "",
               shape: Int = (read().size * 3) % Bot.SHAPES, color: Int = (read().size * 7 + 2) % Bot.COLORS): Bot {
        val taken = all().map { it.id }.toSet()
        var id = Bot.slug(name)
        if (id in taken) { var n = 2; while ("$id-$n" in taken) n++; id = "$id-$n" }
        return save(Bot(id, name.trim().ifEmpty { id }, title.trim(), description.trim(), shape, color, soul, model))
    }

    @Synchronized
    fun duplicate(id: String): Bot? {
        val src = get(id) ?: return null
        val copy = create("${src.name} copy", src.title, src.description, src.soul, src.model, src.shape, src.color)
            .also { it.disabledToolsets = src.disabledToolsets; it.group = src.group; save(it) }
        // Memory travels with the clone, like Hermes' profile duplicate.
        val from = memoryFor(src)
        val to = memoryFor(copy)
        for (target in listOf("memory", "user")) from.file(target).takeIf { it.exists() }?.copyTo(to.file(target), overwrite = true)
        return copy
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (id == Bot.DEFAULT_ID) return false
        val list = read()
        if (!list.removeAll { it.id == id }) return false
        write(list)
        memories.remove(id)
        File(dir, id).deleteRecursively()
        return true
    }

    /** Distinct group labels (alphabetical) → members. */
    fun groups(): Map<String, List<Bot>> =
        all().filter { it.group.isNotBlank() }.groupBy { it.group.trim().lowercase() }.values
            .sortedBy { it.first().group.trim().lowercase() }
            .associate { it.first().group.trim() to it }

    @Synchronized
    fun memoryFor(bot: Bot?): MemoryStore {
        if (bot == null || bot.isDefault) return defaultMemory
        return memories.getOrPut(bot.id) { MemoryStore(File(dir, "${bot.id}/memories")) }
    }
}

/** Text rules shared by bot-to-bot messaging and group rooms (kept pure for tests). */
object BotText {
    private val mention = Regex("(?<![\\w@])@([A-Za-z0-9][A-Za-z0-9_-]{0,31})")

    /** Bots @mentioned in [text] (by id or name slug), in order, without duplicates. */
    fun mentions(text: String, candidates: List<Bot>): List<Bot> {
        val found = mutableListOf<Bot>()
        for (m in mention.findAll(text)) {
            val key = m.groupValues[1].lowercase()
            candidates.firstOrNull { it.id.lowercase() == key || Bot.slug(it.name) == key }
                ?.takeIf { b -> found.none { it.id == b.id } }?.let { found += it }
        }
        return found
    }

    fun mentionsUser(text: String): Boolean =
        mention.findAll(text).any { it.groupValues[1].equals("user", true) }

    /** A group-room reply that means "I have nothing to add". */
    fun isPass(reply: String): Boolean {
        val t = reply.trim().trim('.', '!', '*', '_', '`', '"', ' ').uppercase()
        return t.isEmpty() || t == "PASS" || t == "(PASS)" || t == "[PASS]" || (t.startsWith("PASS") && t.length <= 14)
    }

    fun a2aPrefix(fromName: String, fromId: String) = "Message from 🤖 $fromName (@$fromId): "
}
