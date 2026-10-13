package com.ersaz.hermesmobile.backup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zip helpers (kept free of Android types so they can be unit-tested). */
object Zips {
    /** Writes every file under [dir] as `<prefix>/relative/path`, skipping paths for which [skip] returns true. */
    fun zipDir(dir: File, prefix: String, zip: ZipOutputStream, skip: (String) -> Boolean = { false }): Int {
        var n = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (skip(rel)) return@forEach
            zip.putNextEntry(ZipEntry("$prefix/$rel")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        return n
    }

    /** Extracts entries under [prefix] into [dir]; entries that would escape [dir] ("zip slip") are ignored. */
    fun unzipPrefix(input: ZipInputStream, prefix: String, dir: File, entryHandler: ((String, ByteArray) -> Boolean)? = null): Int {
        var n = 0
        val root = dir.canonicalFile
        generateSequence { input.nextEntry }.forEach { e ->
            if (e.isDirectory) return@forEach
            if (entryHandler != null && !e.name.startsWith("$prefix/")) {
                val bytes = input.readBytes(); if (entryHandler(e.name, bytes)) n++; return@forEach
            }
            if (!e.name.startsWith("$prefix/")) return@forEach
            val target = File(root, e.name.removePrefix("$prefix/")).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) return@forEach
            target.parentFile?.mkdirs(); target.outputStream().use { input.copyTo(it) }; n++
        }
        return n
    }
}

/**
 * Whole-app backup: everything under `$HERMES_HOME` (sessions, memory, skills, bots, MCP servers, cron, usage…)
 * plus the app settings. API keys and sign-in tokens are left out unless the user opts in.
 */
class Backup(private val ctx: Context, private val hermesHome: File) {
    private val secretKeys = setOf("api_key", "nous_access_token", "nous_refresh_token", "telegram_token", "discord_token", "slack_bot_token",
        "slack_app_token", "tavily_key", "brave_key")

    fun export(out: OutputStream, includeSecrets: Boolean): Int = ZipOutputStream(out.buffered()).use { zip ->
        val prefs = ctx.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE).all
        val json = JSONObject()
        prefs.forEach { (k, v) -> if (includeSecrets || k !in secretKeys) json.put(k, v) }
        zip.putNextEntry(ZipEntry("settings.json")); zip.write(json.toString(2).toByteArray()); zip.closeEntry()
        // Rebuildable or private caches stay out.
        Zips.zipDir(hermesHome, "hermes", zip) { it.startsWith("audio/") || it.startsWith("browser/") || it.startsWith("exports/") || it.endsWith(".tmp") }
    }

    /** Returns the number of files restored. Settings are merged over the current ones. */
    fun import(input: InputStream): Int {
        var restored = 0
        ZipInputStream(input.buffered()).use { zip ->
            restored = Zips.unzipPrefix(zip, "hermes", hermesHome) { name, bytes ->
                if (name != "settings.json") false else {
                    val j = JSONObject(String(bytes))
                    val e = ctx.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE).edit()
                    j.keys().forEach { k ->
                        when (val v = j.get(k)) {
                            is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                            is Double -> e.putFloat(k, v.toFloat()); is String -> e.putString(k, v)
                        }
                    }
                    e.apply(); true
                }
            }
        }
        return restored
    }
}
