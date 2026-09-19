package app.lifeos.next.ui.chat

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTimelinePagerTest {
    private val at = Instant.parse("2026-09-19T19:30:00Z")

    @Test
    fun initialPageKeepsNewestItemsAndExposesOlderCount() {
        val timeline = List(100) { index -> item(index) }

        val page = ChatTimelinePager.page(
            timeline,
            ChatTimelinePager.DEFAULT_PAGE_SIZE,
        )

        assertEquals(60, page.items.size)
        assertEquals("image:img-40:1", page.items.first().id)
        assertEquals("image:img-99:1", page.items.last().id)
        assertEquals(100, page.totalCount)
        assertTrue(page.hasOlder)
    }

    @Test
    fun expansionIsBoundedAndEventuallyExposesWholeTimeline() {
        val timeline = List(100) { index -> item(index) }
        val expanded = ChatTimelinePager.expand(ChatTimelinePager.DEFAULT_PAGE_SIZE)
        val page = ChatTimelinePager.page(timeline, expanded)

        assertEquals(100, page.items.size)
        assertFalse(page.hasOlder)
        assertEquals(
            ChatTimelinePager.MAX_VISIBLE_ITEMS,
            ChatTimelinePager.expand(ChatTimelinePager.MAX_VISIBLE_ITEMS),
        )
    }

    private fun item(index: Int): ChatTimelineItem = ChatTimelineItem.Image(
        Photon(
            id = PhotonId("img-" + index),
            revision = 1,
            content = "image " + index,
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = at.plusSeconds(index.toLong()),
            ),
        )
    )
}
