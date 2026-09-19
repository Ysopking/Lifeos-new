package app.lifeos.next.ui.perf

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import app.lifeos.next.ui.PagedPhotonRepositoryFake
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationTimelinePagerTest {
    @Test
    fun pagesConversationWithoutRetainingUnboundedHistory() = runTest {
        val photons = (0 until 300).map { index ->
            Photon(
                id = PhotonId("chat-$index"),
                content = "message-$index",
                provenance = Provenance(
                    source = "test",
                    actor = "user",
                    createdAt = Instant.EPOCH.plusSeconds(index.toLong()),
                ),
                tags = setOf("chat", "chat:user", "conversation:default"),
            )
        }
        val pager = ConversationTimelinePager(
            queries = ProductivePhotonQueryService(PagedPhotonRepositoryFake(photons)),
            pageSize = 32,
            maxLoadedPages = 3,
        )

        val first = pager.refreshFront()
        assertEquals(32, first.photons.size)
        assertTrue(first.hasMore)

        val second = pager.loadMore()
        val third = pager.loadMore()
        assertEquals(64, second.photons.size)
        assertEquals(96, third.photons.size)
        assertFalse(third.hasMore)

        val capped = pager.loadMore()
        assertEquals(96, capped.photons.size)
        assertFalse(capped.hasMore)
        assertEquals("message-299", capped.photons.first().content)
    }

    @Test
    fun frontRefreshAddsNewRevisionWithoutDroppingLoadedOlderPages() = runTest {
        val original = (0 until 80).map { index ->
            Photon(
                id = PhotonId("chat-$index"),
                content = "message-$index",
                provenance = Provenance("test", "user", Instant.EPOCH.plusSeconds(index.toLong())),
                tags = setOf("chat", "chat:user", "conversation:default"),
            )
        }.toMutableList()
        val repository = PagedPhotonRepositoryFake(original)
        val pager = ConversationTimelinePager(
            queries = ProductivePhotonQueryService(repository),
            pageSize = 20,
            maxLoadedPages = 4,
        )

        pager.refreshFront()
        val twoPages = pager.loadMore()

        assertEquals(40, twoPages.photons.size)
        assertTrue(twoPages.photons.any { it.content == "message-40" })
    }
}
