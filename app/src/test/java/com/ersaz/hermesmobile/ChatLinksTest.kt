package com.ersaz.hermesmobile

import androidx.compose.ui.graphics.Color
import com.ersaz.hermesmobile.ui.inline
import org.junit.Assert.*
import org.junit.Test

class ChatLinksTest {
    @Test fun annotatesPlainWebLinksAndKeepsPersianPunctuationOutsideLink() {
        val rendered = inline("ببین https://example.com/docs، ادامه", Color.Blue, Color.Gray, Color.Transparent)
        val links = rendered.getStringAnnotations("URL", 0, rendered.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/docs", links.single().item)
        assertEquals("ببین https://example.com/docs، ادامه", rendered.text)
    }
    @Test fun supportsMarkdownLinksAndLinksInsideBoldButNotInlineCode() {
        val rendered = inline("[Docs](https://example.com) **https://example.org** `https://not-a-link.test`", Color.Blue, Color.Gray, Color.Transparent)
        assertEquals(listOf("https://example.com", "https://example.org"), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
    }
}
