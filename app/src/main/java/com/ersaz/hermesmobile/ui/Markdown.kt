package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/** Markdown renderer for agent replies: fences, tables, images, headings, lists, quotes, bold/italic/code and clickable links. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val blocks = remember(text) { splitBlocks(text) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    SelectionContainer {
        Column(modifier) {
            for (b in blocks) {
                when {
                    b.code -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(skin.surface, RoundedCornerShape(8.dp)).padding(10.dp)
                    ) {
                        if (b.lang.isNotBlank()) Text(b.lang, color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(b.text, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp)
                        }
                    }
                    b.image != null -> MarkdownImage(b.image.first, b.image.second)
                    b.table != null -> MarkdownTable(b.table)
                    else -> {
                        val annotated = inline(b.text, skin.accent, skin.label, skin.surface)
                        androidx.compose.foundation.text.ClickableText(
                            annotated, style = androidx.compose.ui.text.TextStyle(color = skin.text, fontSize = 15.sp, lineHeight = 21.sp),
                            onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } } },
                        )
                    }
                }
            }
        }
    }
}

/** `![alt](src)` where src is an http(s) URL, a data: URI, or a path inside the Linux sandbox. */
@Composable
private fun MarkdownImage(alt: String, src: String) {
    val skin = LocalSkin.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var big by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val bmp = androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, src) {
        value = withContext(Dispatchers.IO) { loadMarkdownImage(ctx, src) }
    }.value
    if (bmp == null) {
        Text("🖼️ $alt", color = skin.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    androidx.compose.foundation.Image(
        bmp.asImageBitmap(), alt,
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { big = true },
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
    )
    if (big) androidx.compose.material3.AlertDialog(
        onDismissRequest = { big = false },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { big = false }) { Text("Close") } },
        text = { androidx.compose.foundation.Image(bmp.asImageBitmap(), alt, Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) },
    )
}

private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
}

private fun loadMarkdownImage(ctx: android.content.Context, src: String): android.graphics.Bitmap? {
    imageCache.get(src)?.let { return it }
    val bytes: ByteArray? = try {
        when {
            src.startsWith("data:") -> android.util.Base64.decode(src.substringAfter("base64,"), android.util.Base64.DEFAULT)
            src.startsWith("http://") || src.startsWith("https://") -> {
                val rt = com.ersaz.hermesmobile.HermesRuntime.get(ctx)
                rt.http.newCall(okhttp3.Request.Builder().url(src).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            }
            else -> {
                val rt = com.ersaz.hermesmobile.HermesRuntime.get(ctx)
                val path = src.removePrefix("file://").removePrefix("hermes://")
                // Only files inside the sandbox rootfs are readable this way.
                val f = rt.sandbox.hostPath(if (path.startsWith("/")) path else "/root/$path")
                if (f.isFile && f.canonicalPath.startsWith(rt.sandbox.rootfs.canonicalPath)) f.readBytes() else null
            }
        }
    } catch (_: Exception) { null }
    bytes ?: return null
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    while (opts.outWidth / sample > 2048) sample *= 2
    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    if (bmp != null) imageCache.put(src, bmp)
    return bmp
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    val skin = LocalSkin.current
    val cols = rows.maxOf { it.size }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        for (c in 0 until cols) {
            Column(Modifier.background(if (c % 2 == 0) skin.surface else skin.surface.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                rows.forEachIndexed { r, row ->
                    Text(inline(row.getOrElse(c) { "" }, skin.accent, skin.label, skin.surface), color = if (r == 0) skin.title else skin.text,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, fontSize = 13.5.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }

private data class Block(val text: String, val code: Boolean, val lang: String = "", val image: Pair<String, String>? = null, val table: List<List<String>>? = null)

private val imageLine = Regex("^\\s*!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)\\s*$")
private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun splitRow(line: String) = line.trim().trim('|').split('|').map { it.trim() }

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val buf = StringBuilder()
    fun flushText() { if (buf.isNotBlank()) out += Block(buf.toString().trimEnd('\n'), false); buf.clear() }
    var inCode = false
    var lang = ""
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            if (inCode) { out += Block(buf.toString().trimEnd('\n'), true, lang); buf.clear(); inCode = false }
            else { flushText(); inCode = true; lang = line.trim().removePrefix("```").trim() }
            i++; continue
        }
        if (!inCode) {
            val im = imageLine.find(line)
            if (im != null) { flushText(); out += Block("", false, image = im.groupValues[1] to im.groupValues[2]); i++; continue }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushText()
                val rows = mutableListOf(splitRow(line)); i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += splitRow(lines[i]); i++ }
                out += Block("", false, table = rows)
                continue
            }
        }
        buf.append(line).append('\n'); i++
    }
    if (buf.isNotBlank()) out += Block(buf.toString().trimEnd('\n'), inCode, lang)
    return out
}

private val inlineRx = Regex("""(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*\s][^*]*\*|_[^_\s][^_]*_|\[[^\]]+\]\([^)]+\))""")

fun inline(src: String, accent: androidx.compose.ui.graphics.Color, label: androidx.compose.ui.graphics.Color,
           codeBg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val lines = src.lines()
    lines.forEachIndexed { idx, raw ->
        var line = raw
        val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
        when {
            heading != null -> {
                val level = heading.groupValues[1].length
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent, fontSize = (20 - level * 1.5).coerceAtLeast(14.0).sp)) {
                    appendInline(heading.groupValues[2], accent, label, codeBg)
                }
            }
            line.trimStart().startsWith("> ") -> withStyle(SpanStyle(color = label, fontStyle = FontStyle.Italic)) {
                append("▍ "); appendInline(line.trimStart().removePrefix("> "), accent, label, codeBg)
            }
            Regex("^\\s*[-*+]\\s+").containsMatchIn(line) -> {
                val indent = line.takeWhile { it == ' ' }.length / 2
                append("  ".repeat(indent)); withStyle(SpanStyle(color = accent)) { append("• ") }
                appendInline(line.replaceFirst(Regex("^\\s*[-*+]\\s+"), ""), accent, label, codeBg)
            }
            line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> withStyle(SpanStyle(color = label)) { append("────────────") }
            else -> appendInline(line, accent, label, codeBg)
        }
        if (idx < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, accent: androidx.compose.ui.graphics.Color,
                                                 label: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    var last = 0
    for (m in inlineRx.findAll(text)) {
        append(text.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = label)) { append(t.trim('`')) }
            t.startsWith("[") -> {
                val label2 = t.substring(1, t.indexOf(']'))
                val url = t.substring(t.indexOf('(') + 1, t.length - 1)
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label2) }
                pop()
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
