package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.background
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

/** Small Markdown renderer for agent replies: fences, headings, lists, quotes, bold/italic/code/links. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val blocks = remember(text) { splitBlocks(text) }
    SelectionContainer {
        Column(modifier) {
            for (b in blocks) {
                if (b.code) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(skin.surface, RoundedCornerShape(8.dp)).padding(10.dp)
                    ) {
                        if (b.lang.isNotBlank()) Text(b.lang, color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(b.text, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp)
                        }
                    }
                } else {
                    Text(inline(b.text, skin.accent, skin.label, skin.surface), color = skin.text, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }

private data class Block(val text: String, val code: Boolean, val lang: String = "")

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out += Block(buf.toString().trimEnd('\n'), true, lang); buf.clear(); inCode = false
            } else {
                if (buf.isNotBlank()) out += Block(buf.toString().trimEnd('\n'), false)
                buf.clear(); inCode = true; lang = line.trim().removePrefix("```").trim()
            }
            continue
        }
        buf.append(line).append('\n')
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
