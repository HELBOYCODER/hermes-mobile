package com.ersaz.hermesmobile.bridge

import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.gateway.ChatOutput
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Tiny HTTP server on 127.0.0.1 so the `hermes` CLI inside the PRoot sandbox can talk to the
 * app's agent (the sandbox shares the phone's network namespace). Auth is a random bearer
 * token written to $HERMES_HOME/.bridge_token, readable only from inside the app's sandbox.
 */
class LocalBridge(private val rt: HermesRuntime) {
    private var server: ServerSocket? = null
    var port = 0
        private set
    private val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun start() {
        if (server != null) return
        val s = try { ServerSocket(8642, 16, InetAddress.getByName("127.0.0.1")) }
        catch (_: Exception) { ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) }
        server = s
        port = s.localPort
        File(rt.hermesHome, ".bridge_port").writeText(port.toString())
        File(rt.hermesHome, ".bridge_token").writeText(token)
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread({ handle(c) }, "hermes-bridge-conn").apply { isDaemon = true; start() }
            }
        }, "hermes-bridge").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) = socket.use { sock ->
        val input = sock.getInputStream().buffered()
        val out = sock.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len).also { b -> var r = 0; while (r < len) { val n = input.read(b, r, len - r); if (n < 0) break; r += n } }
        if (headers["authorization"] != "Bearer $token") return respond(out, 401, "unauthorized\n")
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        when {
            path.startsWith("/v1/chat") -> chat(out, headers["x-hermes-session"] ?: "terminal", String(body, Charsets.UTF_8))
            path.startsWith("/v1/health") -> respond(out, 200, "ok\n")
            else -> respond(out, 404, "not found\n")
        }
    }

    private fun chat(out: OutputStream, sessionKey: String, text: String) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
        out.flush()
        val sink = object : ChatOutput {
            override val streams = true
            override fun stream(delta: String) = write(delta)
            override suspend fun send(text: String) = write(text + "\n")
            private fun write(s: String) {
                try { synchronized(out) { out.write(s.toByteArray()); out.flush() } } catch (_: Exception) {}
            }
        }
        val runner = rt.gateway.localRunner(sessionKey.replace(Regex("[^A-Za-z0-9_-]"), "_"), sink)
        runBlocking { runner.runAndWait(text) }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        out.write("HTTP/1.1 $code X\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
