package app.lifeos.next.ui.memory

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchIndexTest {
    @Test
    fun equivalentRevisionSetReusesIndexAcrossInputOrder() {
        val first = photon("a", 1, "Alpha", 1)
        val second = photon("b", 1, "Beta", 2)
        val initial = MemorySearchIndex.build(listOf(first, second), maxIndexedSources = 2)

        val reused = MemorySearchIndex.reuseOrBuild(
            existing = initial,
            photons = listOf(second, first),
            maxIndexedSources = 2,
        )

        assertSame(initial, reused)
    }

    @Test
    fun revisionChangeRebuildsIndexAndUsesLatestRevision() {
        val original = photon("a", 1, "Alpha", 1)
        val initial = MemorySearchIndex.build(listOf(original))
        val revised = photon("a", 2, "Gamma", 1)

        val rebuilt = MemorySearchIndex.reuseOrBuild(initial, listOf(original, revised))

        assertNotSame(initial, rebuilt)
        assertEquals(2L, rebuilt.source(PhotonId("a"))?.revision)
        assertTrue(rebuilt.matchesSource(PhotonId("a"), "gamma"))
        assertFalse(rebuilt.matchesSource(PhotonId("a"), "alpha"))
    }

    @Test
    fun boundedPrecomputationFallsBackWithoutLosingOldSearchMatches() {
        val photons = (0 until 8).map { index ->
            photon(
                id = "p-$index",
                revision = 1,
                content = "Needle-$index",
                second = index.toLong(),
            )
        }
        val index = MemorySearchIndex.build(photons, maxIndexedSources = 2)

        assertEquals(2, index.indexedSourceCount)
        assertEquals(8, index.latestSourceCount)
        assertTrue(index.matchesSource(PhotonId("p-0"), "needle-0"))
        assertTrue(index.matchesSource(PhotonId("p-7"), "needle-7"))
    }

    @Test
    fun metadataTagsSourceAndActorRemainSearchable() {
        val item = Photon(
            id = PhotonId("meta"),
            content = "Inhalt",
            provenance = Provenance(
                source = "calendar-adapter",
                actor = "user",
                createdAt = Instant.EPOCH,
            ),
            tags = setOf("place:Berlin", "calendar"),
        )
        val index = MemorySearchIndex.build(listOf(item))

        assertTrue(index.matchesSource(item.id, "berlin"))
        assertTrue(index.matchesSource(item.id, "calendar-adapter"))
        assertTrue(index.matchesSource(item.id, "user"))
    }

    private fun photon(
        id: String,
        revision: Long,
        content: String,
        second: Long,
    ): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "test-source",
            actor = "user",
            createdAt = Instant.EPOCH.plusSeconds(second),
        ),
        tags = setOf("chat"),
    )
}
