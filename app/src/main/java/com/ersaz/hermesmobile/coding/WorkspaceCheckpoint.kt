package com.ersaz.hermesmobile.coding

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

sealed class RestoreResult {
    data object Restored : RestoreResult()
    data class Conflict(val paths: List<String>) : RestoreResult()
}
/** Snapshots only explicitly selected project files, with conflict detection before any restore. */
class WorkspaceCheckpoint(private val root: File, private val storage: File = File(root, ".hermes/checkpoints")) {
    fun capture(paths: List<String>): String = synchronized(lock) {
        require(paths.isNotEmpty() && paths.size <= 100)
        var total = 0L
        val entries = JSONArray()
        paths.distinct().forEach { path ->
            val f = resolveProjectFile(root, path)
            require(!f.isDirectory && (!f.exists() || f.isFile))
            require(f.length() <= 1_048_576) { "Checkpoint file limit: 1 MiB" }
            total += f.length(); require(total <= 10_485_760) { "Checkpoint limit: 10 MiB" }
            entries.put(JSONObject().put("path", f.relativeTo(root.canonicalFile).invariantSeparatorsPath)
                .put("existed", f.exists()).put("before", if (f.exists()) Base64.getEncoder().encodeToString(f.readBytes()) else ""))
        }
        val id = UUID.randomUUID().toString()
        save(id, JSONObject().put("id", id).put("created", System.currentTimeMillis()).put("sealed", false).put("files", entries))
        id
    }
    fun seal(id: String) = synchronized(lock) {
        val data = load(id)
        val entries = data.getJSONArray("files")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            e.put("after", fingerprint(resolveProjectFile(root, e.getString("path"))))
        }
        data.put("sealed", true); save(id, data)
    }
    fun restore(id: String): RestoreResult = synchronized(projectMutationLock) { synchronized(lock) {
        val data = load(id)
        val entries = data.getJSONArray("files")
        val conflicts = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { e ->
            !data.optBoolean("sealed") || fingerprint(resolveProjectFile(root, e.getString("path"))) != e.optString("after")
        }.map { it.getString("path") }
        if (conflicts.isNotEmpty()) return@synchronized RestoreResult.Conflict(conflicts)
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i); val f = resolveProjectFile(root, e.getString("path"))
            if (e.getBoolean("existed")) {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, ".${f.name}.${UUID.randomUUID()}.restore")
                tmp.writeBytes(Base64.getDecoder().decode(e.getString("before")))
                check(tmp.renameTo(f)) { "Cannot restore ${f.name}" }
            } else if (f.exists()) check(f.delete()) { "Cannot remove unchanged generated file" }
        }
        // Single-use restore: a second click must not overwrite later work.
        data.put("sealed", false);save(id, data)
        RestoreResult.Restored
    } }
    fun preview(id: String): String = synchronized(lock) {
        val entries = load(id).getJSONArray("files")
        (0 until entries.length()).joinToString("\n\n") { i ->
            val entry = entries.getJSONObject(i)
            val file = resolveProjectFile(root, entry.getString("path"))
            val before = if (entry.getBoolean("existed")) String(Base64.getDecoder().decode(entry.getString("before")), Charsets.UTF_8) else "[absent]"
            val current = if (file.isFile && file.length() <= 1_048_576) file.readText() else "[absent or too large]"
            "${entry.getString("path")}\n--- Checkpoint (restore target)\n${before.take(4000)}\n+++ Current file\n${current.take(4000)}"
        }.take(16000)
    }
    fun list(): List<String> = storage.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
        ?.take(20)?.map { it.nameWithoutExtension }.orEmpty()
    private fun load(id: String): JSONObject {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return JSONObject(File(storage, "$id.json").readText())
    }
    private fun save(id: String, data: JSONObject) {
        storage.mkdirs(); val f = File(storage, "$id.json"); val tmp = File(storage, "$id.tmp")
        tmp.writeText(data.toString());check(tmp.renameTo(f))
    }
    companion object {
        private val lock = Any()
        fun fingerprint(file: File): String = if (!file.exists()) "absent" else {
            require(file.isFile && file.length() <= 1_048_576) { "File changed beyond checkpoint bounds" }
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }
}
