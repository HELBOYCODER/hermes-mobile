package com.ersaz.hermesmobile.coding

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Runtime evidence is stored outside the agent's project when a private store is provided. */
class CodingTaskStore(root: File, private val storage: File = File(root, ".specify/hermes-state.json")) {
    fun read(): JSONObject = synchronized(lock) {
        if (!storage.exists()) JSONObject().put("tasks", JSONArray()).put("runs", JSONObject())
        else {
            require(storage.length() <= 4_194_304) { "Evidence file exceeds the safe read limit; archive it from Files before continuing" }
            JSONObject(storage.readText())
        }
    }
    fun update(id: String, text: String, status: String, runId: String? = null) = synchronized(lock) {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        require(text.length in 1..4000 && status in setOf("todo", "doing", "done"))
        val root = read()
        if (status == "done") {
            val run = root.getJSONObject("runs").optJSONObject(runId ?: "")
            require(run != null && run.optString("status") == "PASSED" && run.optInt("exit_code", -1) == 0) { "Completion requires a saved passing test run" }
        }
        val tasks = root.getJSONArray("tasks")
        val index = (0 until tasks.length()).firstOrNull { tasks.getJSONObject(it).optString("id") == id }
        require(index != null || tasks.length() < 64) { "Task limit reached" }
        val task = JSONObject().put("id", id).put("text", text).put("status", status).put("run_id", runId ?: JSONObject.NULL)
        if (index == null) tasks.put(task) else tasks.put(index, task)
        save(root)
    }
    fun recordRun(id: String, exitCode: Int?, status: String, command: String = "", output: String = "") = synchronized(lock) {
        val root = read(); val runs = root.getJSONObject("runs")
        while (runs.length() >= 100) runs.keys().asSequence().firstOrNull()?.let { runs.remove(it) }
        runs.put(id, JSONObject().put("status", status).put("exit_code", exitCode ?: JSONObject.NULL)
            .put("command", command.take(4000)).put("output", output.take(16000)).put("at", System.currentTimeMillis()))
        save(root)
    }
    private fun save(root: JSONObject) {
        storage.parentFile?.mkdirs()
        val tmp = File(storage.parentFile, storage.name + ".tmp")
        tmp.writeText(root.toString());check(tmp.renameTo(storage))
    }
    companion object { private val lock = Any() }
}
