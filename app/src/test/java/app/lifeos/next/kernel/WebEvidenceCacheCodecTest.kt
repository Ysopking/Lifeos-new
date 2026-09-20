package app.lifeos.next.kernel

import kotlin.test.Test
import kotlin.test.assertEquals

class WebEvidenceCacheCodecTest {
    @Test
    fun `cache codec round trips source page payload exactly`() {
        val entry = WebEvidenceCacheEntry(
            title = "Bescheid & Widerspruch",
            url = "https://example.org/a?x=1&y=2",
            passage = "Eine Passage\nmit mehreren Zeilen und Umlauten: äöü.",
        )

        assertEquals(entry, WebEvidenceCacheCodec.decode(WebEvidenceCacheCodec.encode(entry)))
    }
}
