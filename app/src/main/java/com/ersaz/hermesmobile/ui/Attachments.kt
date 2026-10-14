package com.ersaz.hermesmobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ersaz.hermesmobile.HermesRuntime
import java.io.File

/** A file the user attached in chat. Non-image files are copied into the sandbox so the agent can use them. */
class Attachment(val name: String, val guestPath: String, val mime: String, val size: Long) {
    val isImage get() = mime.startsWith("image/")
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

/** Copy any picked document into /root/uploads/ inside the sandbox (any type, any size). */
fun importAttachment(context: Context, uri: Uri): Attachment? = runCatching {
    val rt = HermesRuntime.get(context)
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) c.getString(ni)?.let { name = it }
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().ifBlank { "file" }
    val dir = File(rt.sandbox.rootfs, "root/uploads").apply { mkdirs() }
    var out = File(dir, safe)
    var n = 1
    while (out.exists()) {
        val dot = safe.lastIndexOf('.')
        out = File(dir, if (dot > 0) safe.substring(0, dot) + "_$n" + safe.substring(dot) else safe + "_$n"); n++
    }
    resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    Attachment(out.name, "/root/uploads/${out.name}", resolver.getType(uri) ?: guessMime(out.name), out.length())
}.getOrNull()

private fun guessMime(name: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** The note appended to the user's message so the agent knows where the files are. */
fun attachmentNote(files: List<Attachment>): String =
    if (files.isEmpty()) "" else
        "\n\n[Attached files — saved in the sandbox, open them with the file/terminal tools]\n" +
            files.joinToString("\n") { "- ${it.guestPath}  (${it.mime}, ${formatSize(it.size)})" }
