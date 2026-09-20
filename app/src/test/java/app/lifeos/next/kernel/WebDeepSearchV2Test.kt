package app.lifeos.next.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDeepSearchV2Test {
    @Test
    fun searchParserRanksSemanticQueryOverlapAndKeepsHttpsTargets() {
        val html = """
            <a class="result__a" href="https://example.org/a">Jobcenter Widerspruchsfrist</a>
            <div class="result__snippet">Ein Bescheid kann eine Widerspruchsfrist enthalten.</div>
            <a class="result__a" href="https://example.org/b">Unrelated page</a>
            <div class="result__snippet">Nothing relevant here.</div>
        """.trimIndent()

        val hits = WebSearchHtmlParser.parse(html, "Jobcenter Bescheid Widerspruchsfrist")

        assertEquals(1, hits.size)
        assertEquals("https://example.org/a", hits.single().url)
        assertTrue(hits.single().confidence > 0.4)
    }

    @Test
    fun documentExtractorRemovesActiveContentAndSelectsRelevantPassage() {
        val html = """
            <html>
              <head><title>Bescheid und Widerspruch</title><script>evil()</script></head>
              <body>
                <p>Navigation ohne fachlichen Bezug und mit ausreichend vielen Zeichen.</p>
                <article>
                  <p>Für den Jobcenter Bescheid ist die Widerspruchsfrist eine wichtige Angabe.</p>
                </article>
              </body>
            </html>
        """.trimIndent()

        val extracted = WebDocumentTextExtractor.extract(
            html,
            "Jobcenter Bescheid Widerspruchsfrist",
        )

        assertTrue(extracted.passage.contains("Widerspruchsfrist"))
        assertFalse(extracted.passage.contains("evil"))
    }
}
