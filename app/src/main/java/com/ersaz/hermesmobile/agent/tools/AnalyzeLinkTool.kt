package com.ersaz.hermesmobile.agent.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Public evidence only: authentication walls are reported, never bypassed. */
class AnalyzeLinkTool : Tool("analyze_link", "web",
    "Fetch available captions and metadata from a public YouTube or Instagram link. Returns attributed evidence and downloadable files. Metadata alone is not a transcript or full visual analysis.", "🎞️") {
    override val parameters = obj("url" to str("Public HTTPS YouTube video or Instagram post/reel URL"), required = listOf("url"))
    override suspend fun run(args: JSONObject, ctx: ToolContext): String = jobs.withLock {
        if (ctx.agent.tools().none { it.name == "terminal" }) return@withLock fail("Enable terminal tools for local media extraction")
        val url = args.getString("url")
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withLock fail("Invalid URL")
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.host?.lowercase() !in hosts)
            return@withLock fail("Use a public HTTPS YouTube or Instagram link")
        if (!ctx.runtime.sandbox.installBundledMedia()) return@withLock fail("Bundled media runtime could not be installed")
        val out = "/root/outputs/media-${UUID.randomUUID()}"
        try {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val model = java.io.File(ctx.runtime.app.filesDir, "media-models/ggml-tiny.bin")
        val result = JSONObject(TerminalTool().run(JSONObject()
            .put("command", "python3 /root/.hermes/media/extract_media.py ${quote(url)} --output ${quote(out)}${if (model.isFile) " --audio" else ""}")
            .put("workdir", "/root").put("timeout", 90), ctx))
        if (result.optInt("exit_code", -1) != 0) return@withLock fail("Media unavailable or extraction failed. No transcript was obtained.")
        val evidenceFile = ctx.runtime.sandbox.hostPath("$out/evidence.json")
        if (!evidenceFile.isFile || evidenceFile.length() > 2_000_000) return@withLock fail("No bounded media evidence produced")
        val evidence = JSONObject(evidenceFile.readText())
        val cli = runCatching { JSONObject(result.optString("output")) }.getOrNull()
        val audioPath = cli?.optString("audio_path").orEmpty()
        if (model.isFile && audioPath == "$out/audio.wav") {
            val audio = ctx.runtime.sandbox.hostPath(audioPath)
            try {
                com.ersaz.hermesmobile.media.WhisperBridge.prepare()
                val transcript = kotlinx.coroutines.coroutineScope {
                    val computation = async(kotlinx.coroutines.Dispatchers.IO) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        com.ersaz.hermesmobile.media.WhisperBridge.transcribe(model.absolutePath, audio.absolutePath)
                    }
                    try { computation.await() }
                    catch (e: kotlinx.coroutines.CancellationException) {
                        com.ersaz.hermesmobile.media.WhisperBridge.cancel(); throw e
                    }
                }
                val spoken = JSONObject(transcript)
                evidence.put("coverage", spoken.getString("coverage")).put("method", spoken.getString("method"))
                    .put("segments", spoken.getJSONArray("segments")).put("limitation", "Audio transcript only; visual content was not analyzed.")
                val segments = spoken.getJSONArray("segments")
                ctx.runtime.sandbox.hostPath("$out/transcript.txt").writeText((0 until segments.length()).joinToString("\n") {
                    val segment = segments.getJSONObject(it)
                    "[${segment.optDouble("start")}s] ${segment.optString("text")}"
                })
                evidenceFile.writeText(evidence.toString())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: LinkageError) { evidence.put("limitation", "Native transcription engine unavailable on this device.") }
            catch (e: Exception) { evidence.put("limitation", "Offline transcription failed; only the listed metadata was obtained.") }
            finally { audio.delete() }
        }
        listOf("evidence.json", "transcript.txt").forEach { name ->
            val file = ctx.runtime.sandbox.hostPath("$out/$name")
            if (file.isFile) ctx.listener.onFile(ctx.runtime.generatedFiles.capture(file).absolutePath)
        }
        evidence.toString().take(60000)
        } finally {
            ctx.runtime.sandbox.hostPath(out).listFiles()?.filter { it.name.startsWith("audio.") }?.forEach { it.delete() }
        }
    }
    companion object {
        private val jobs = Mutex()
        private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "instagram.com", "www.instagram.com")
        fun isBareMediaLink(text: String): Boolean {
            if (text.length > 2048 || text.any { it.isWhitespace() }) return false
            val u = runCatching { URI(text) }.getOrNull() ?: return false
            return u.scheme == "https" && u.host?.lowercase() in hosts && u.userInfo == null && u.port == -1
        }
    }
}
