package com.ersaz.hermesmobile.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory ring buffer so quiet failures (MCP, sign-in, sub-agents, browser) are visible in the app. */
class AppLog(private val capacity: Int = 600) {
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(level: Char, tag: String, msg: String) {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast("${fmt.format(Date())} $level/$tag ${msg.replace('\n', ' ').take(400)}")
    }

    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun e(tag: String, msg: String) = add('E', tag, msg)

    @Synchronized fun lines(): List<String> = buf.toList()
    @Synchronized fun clear() = buf.clear()
}
