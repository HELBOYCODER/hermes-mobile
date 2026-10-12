package com.ersaz.hermesmobile.sandbox

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Background sandbox processes started with terminal(background=true). */
class ProcessRegistry(private val sandbox: Sandbox) {

    class Entry(val id: String, val command: String, val process: Process, val startedAt: Long) {
        val output = StringBuilder()
        @Volatile var readOffset = 0
        val isRunning get() = process.isAlive
        val exitCode get() = if (process.isAlive) null else process.exitValue()
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val counter = AtomicInteger(0)

    fun start(command: String, cwd: String): Entry {
        val id = "proc_${counter.incrementAndGet()}"
        val wrapper = "cd ${Sandbox.shQuote(cwd)} 2>/dev/null || cd /root; exec /bin/sh -c ${Sandbox.shQuote(command)}"
        val pb = sandbox.processBuilder(listOf("/bin/sh", "-c", wrapper))
        pb.redirectErrorStream(true)
        val p = pb.start()
        val e = Entry(id, command, p, System.currentTimeMillis())
        entries[id] = e
        Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(e.output) {
                            e.output.append(buf, 0, n)
                            if (e.output.length > 400_000) {
                                val cut = e.output.length - 200_000
                                e.output.delete(0, cut)
                                e.readOffset = maxOf(0, e.readOffset - cut)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        return e
    }

    fun get(id: String) = entries[id]
    fun list(): List<Entry> = entries.values.sortedBy { it.startedAt }

    fun poll(id: String): String? {
        val e = entries[id] ?: return null
        val new = synchronized(e.output) {
            val s = e.output.substring(e.readOffset.coerceAtMost(e.output.length))
            e.readOffset = e.output.length
            s
        }
        return TerminalSanitizer.clean(new)
    }

    fun log(id: String, tail: Int = 200): String? {
        val e = entries[id] ?: return null
        val all = synchronized(e.output) { e.output.toString() }
        return TerminalSanitizer.clean(all).lines().takeLast(tail).joinToString("\n")
    }

    fun write(id: String, data: String): Boolean {
        val e = entries[id] ?: return false
        return try {
            e.process.outputStream.write(data.toByteArray()); e.process.outputStream.flush(); true
        } catch (_: Exception) { false }
    }

    fun waitFor(id: String, timeoutSec: Int): Boolean {
        val e = entries[id] ?: return false
        return e.process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
    }

    fun kill(id: String): Boolean {
        val e = entries[id] ?: return false
        e.process.destroyForcibly()
        return true
    }

    fun killAll(): Int {
        var n = 0
        entries.values.filter { it.isRunning }.forEach { it.process.destroyForcibly(); n++ }
        return n
    }
}
