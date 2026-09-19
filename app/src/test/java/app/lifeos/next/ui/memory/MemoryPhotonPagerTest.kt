package app.lifeos.next.ui.memory

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

class MemoryPhotonPagerTest {
    @Test
    fun largeHistoryIsBoundedByConfiguredPageWindow() = runTest {
        val photons = (0 until 2_000).map { index ->
            Photon(
                id = PhotonId("memory-$index"),
                content = "memory-$index",
                provenance = Provenance(
                    source = "test",
                    actor = "user",
                    createdAt = Instant.EPOCH.plusSeconds(index.toLong()),
                ),
                tags = setOf("memory-source"),
            )
        }
        val pager = MemoryPhotonPager(
            queries = ProductivePhotonQueryService(PagedPhotonRepositoryFake(photons)),
            pageSize = 64,
            maxLoadedPages = 2,
        )

        val first = pager.refreshFront()
        assertEquals(64, first.photons.size)
        assertTrue(first.hasMore)

        val second = pager.loadMore()
        assertEquals(128, second.photons.size)
        assertFalse(second.hasMore)
        assertEquals("memory-1999", second.photons.first().content)
    }

    @Test
    fun searchIndexBuiltFromWindowDoesNotRetainRepositoryHistory() = runTest {
        val photons = (0 until 500).map { index ->
            Photon(
                id = PhotonId("source-$index"),
                content = "Needle-$index",
                provenance = Provenance("test", "user", Instant.EPOCH.plusSeconds(index.toLong())),
            )
        }
        val pager = MemoryPhotonPager(
            queries = ProductivePhotonQueryService(PagedPhotonRepositoryFake(photons)),
            pageSize = 50,
            maxLoadedPages = 2,
        )
        val window = pager.refreshFront()
        val index = MemorySearchIndex.build(
            window.photons,
            maxIndexedSources = MemoryPhotonPager.DEFAULT_MAX_LOADED_PHOTONS,
        )

        assertEquals(50, index.latestSourceCount)
        assertTrue(index.matchesSource(PhotonId("source-499"), "needle-499"))
        assertFalse(index.matchesSource(PhotonId("source-0"), "needle-0"))
    }
}
