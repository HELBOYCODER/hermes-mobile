package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.core.OpenCodeCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeCatalogTest {
    @Test fun includesOnlyDocumentedFreeChatCompletionModels() {
        val payload = """
            {"object":"list","data":[
              {"id":"cat-jam-free"},
              {"id":"deepseek-v4.1-flash-free"},
              {"id":"jev-1.13-free"},
              {"id":"muse-spark-1.3-contributor-free"},
              {"id":"gpt-6-sol"},
              {"id":"cat-jam-free"},
              {"id":"unknown-free"},
              {"invalid":"missing id"}
            ]}
        """.trimIndent()
        assertEquals(listOf("cat-jam-free", "deepseek-v4.1-flash-free"),
            OpenCodeCatalog.freeChatIds(payload))
    }

    @Test fun handlesEmptyCatalog() {
        assertEquals(emptyList<String>(), OpenCodeCatalog.freeChatIds("""{"data":[]}"""))
        assertEquals(emptyList<String>(), OpenCodeCatalog.freeChatIds("""{}"""))
    }
}
