package com.ersaz.hermesmobile.files

import java.io.File
import java.security.MessageDigest

/** Immutable delivered snapshots survive later edits or deletion of the working file. */
class GeneratedFiles(private val root: File, val directory: File) {
    @Synchronized fun capture(source: File): File {
        val file = source.canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "Choose an existing file inside the Linux sandbox" }
        require(file.length() <= 512L * 1024 * 1024) { "File exceeds the 512 MB attachment limit" }
        directory.mkdirs()
        val temporary = File.createTempFile("delivery-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            file.inputStream().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= 512L * 1024 * 1024) { "File exceeds the attachment limit" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val parent = File(directory, hash).apply { mkdirs() }
            val target = File(parent, safeName(file.name))
            if (!target.exists()) check(temporary.renameTo(target)) { "Could not save chat attachment" }
            return target
        } finally { temporary.delete() }
    }

    fun isDelivered(file: File): Boolean = file.isFile && file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())

    companion object {
        fun safeName(name: String): String {
            val clean = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\p{Cntrl}]"), "_").trim()
            if (clean.isBlank() || clean == "." || clean == "..") return "download"
            var end = 0
            while (end < clean.length) {
                val next = end + Character.charCount(clean.codePointAt(end))
                if (clean.substring(0, next).toByteArray(Charsets.UTF_8).size > 180) break
                end = next
            }
            return clean.substring(0, end).ifBlank { "download" }
        }
    }
}
