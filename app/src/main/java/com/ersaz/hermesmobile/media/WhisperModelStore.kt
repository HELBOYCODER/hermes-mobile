package com.ersaz.hermesmobile.media

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Verified downloads never replace a working model until the entire stream is checked. */
class WhisperModelStore(private val directory: File) {
    fun install(input: InputStream, sha: String, maxBytes: Long = 100L * 1024 * 1024): File = synchronized(lock) {
        require(sha.matches(Regex("[a-fA-F0-9]{64}")) && maxBytes > 0)
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("model-", ".partial", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            input.use { source -> temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    count += n; require(count <= maxBytes) { "Model exceeds download limit" }
                    digest.update(buffer, 0, n); out.write(buffer, 0, n)
                }
            } }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(sha, true)) { "Model checksum mismatch" }
            val target = File(directory, "ggml-tiny.bin")
            check(temp.renameTo(target)) { "Could not commit verified model" }
            target
        } finally { temp.delete() }
    }
    companion object { private val lock = Any() }
}
