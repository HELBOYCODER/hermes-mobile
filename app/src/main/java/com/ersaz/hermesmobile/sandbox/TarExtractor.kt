package com.ersaz.hermesmobile.sandbox

import android.system.Os
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal tar.gz extractor (ustar + GNU long names + pax path) that preserves
 * symlinks, hardlinks and permission bits — enough for an Alpine minirootfs.
 * Hardlinks become symlinks because proot's --link2symlink does the same at runtime.
 */
object TarExtractor {

    fun extract(input: InputStream, dest: File, onEntry: (String) -> Unit = {}) {
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        GZIPInputStream(input.buffered(1 shl 16)).use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null
            val deferredLinks = mutableListOf<Pair<File, String>>()
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break
                var name = cstr(header, 0, 100)
                val mode = octal(header, 100, 8).toInt()
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                var link = cstr(header, 157, 100)
                val magic = cstr(header, 257, 6)
                if (magic.startsWith("ustar")) {
                    val prefix = cstr(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }
                longName?.let { name = it; longName = null }
                longLink?.let { link = it; longLink = null }

                when (type) {
                    'L' -> { longName = String(readData(gz, size)).trimEnd('\u0000'); continue }
                    'K' -> { longLink = String(readData(gz, size)).trimEnd('\u0000'); continue }
                    'x', 'g' -> {
                        val pax = String(readData(gz, size))
                        parsePax(pax)["path"]?.let { if (type == 'x') longName = it }
                        parsePax(pax)["linkpath"]?.let { if (type == 'x') longLink = it }
                        continue
                    }
                }

                val rel = name.removePrefix("./").trimEnd('/')
                if (rel.isEmpty()) { skip(gz, size); continue }
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(destCanon) && !out.absolutePath.startsWith(destCanon)) {
                    skip(gz, size); continue // path traversal guard
                }
                onEntry(rel)
                when (type) {
                    '5' -> { out.mkdirs(); chmod(out, mode or 0b111_000_000) }
                    '2' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        try { Os.symlink(link, out.absolutePath) } catch (_: Exception) {}
                    }
                    '1' -> deferredLinks += out to link.removePrefix("./")
                    '0', '\u0000', '7' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        out.outputStream().use { os -> copyN(gz, os, size) }
                        chmod(out, mode)
                        skipPadding(gz, size)
                        continue
                    }
                    else -> {}
                }
                skip(gz, size)
            }
            for ((file, target) in deferredLinks) {
                file.parentFile?.mkdirs()
                file.delete()
                try { Os.symlink("/$target", file.absolutePath) } catch (_: Exception) {}
            }
        }
    }

    private fun parsePax(s: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (line in s.split('\n')) {
            val sp = line.indexOf(' ')
            val eq = line.indexOf('=')
            if (sp in 0 until eq) m[line.substring(sp + 1, eq)] = line.substring(eq + 1)
        }
        return m
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.absolutePath, mode and 0b111_111_111) } catch (_: Exception) {}
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 for large values
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skipPadding(input, size)
        return data
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        if (size <= 0) return
        copyN(input, NullSink, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) copyN(input, NullSink, pad)
    }
}

private object NullSink : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
