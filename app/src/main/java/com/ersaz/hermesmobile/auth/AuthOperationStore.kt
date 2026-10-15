package com.ersaz.hermesmobile.auth

import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Opaque return tickets only: no OAuth code, token or credentials in app links. */
class AuthOperationStore(private val file: File) {
    @Synchronized fun create(kind: String, expiresAtMs: Long): String {
        val root = read()
        while (root.length() >= 32) root.keys().asSequence().firstOrNull()?.let { root.remove(it) }
        val id = UUID.randomUUID().toString()
        root.put(id, JSONObject().put("kind", kind).put("expires", expiresAtMs))
        write(root)
        return id
    }
    @Synchronized fun consume(id: String, nowMs: Long): Boolean {
        val root = read()
        val op = root.optJSONObject(id) ?: return false
        root.remove(id)
        write(root)
        return nowMs < op.optLong("expires")
    }
    private fun read() = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private fun write(root: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString())
        check(temp.renameTo(file)) { "Cannot persist browser return" }
    }
}
