package com.ersaz.hermesmobile.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SendFileTool : Tool(
    "send_file", "file",
    "Attach an existing generated file to this conversation with Download/Open/Share actions. Supports documents, images, archives, code and APKs. Prefer /root/outputs for final deliverables. Never claim delivery from a path alone; call this tool.", "📎",
) {
    override val parameters = obj("path" to str("Existing sandbox file path"), required = listOf("path"))
    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val path = args.optString("path")
        if (path.isBlank()) return@withContext fail("path is required")
        val file = ctx.runtime.generatedFiles.capture(ctx.runtime.sandbox.hostPath(path, ctx.agent.cwd))
        ctx.listener.onFile(file.absolutePath)
        ok("attached" to true, "name" to file.name, "bytes" to file.length())
    }
}
