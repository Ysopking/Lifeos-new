package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotonIngressMarkerStoreTest {
    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }

    @Test
    fun absentMarkerIsOriginAndDerivedMarkerIsDurableAndIdempotent() = runTest {
        val repository = MemoryPhotonRepository()
        val photon = photon("derived-target")

        assertEquals(PhotonIngressMode.ORIGIN, PhotonIngressMarkerStore.mode(repository, photon))

        val first = PhotonIngressMarkerStore.mark(repository, photon, PhotonIngressMode.DERIVED)
        val second = PhotonIngressMarkerStore.mark(repository, photon, PhotonIngressMode.DERIVED)

        assertEquals(first, second)
        assertEquals(PhotonIngressMode.DERIVED, PhotonIngressMarkerStore.mode(repository, photon))
        assertTrue("life-memory-management" in first.tags)
        assertEquals(setOf(photon.id), first.provenance.parentIds)
    }

    @Test
    fun onePhotonRevisionCannotBeReclassifiedOrMatchedToDifferentState() = runTest {
        val repository = MemoryPhotonRepository()
        val photon = photon("stable-target")
        PhotonIngressMarkerStore.mark(repository, photon, PhotonIngressMode.DERIVED)

        assertFailsWith<IllegalStateException> {
            PhotonIngressMarkerStore.mark(repository, photon, PhotonIngressMode.REPLAY)
        }

        val conflictingState = photon.copy(content = "changed without revision")
        assertFailsWith<IllegalArgumentException> {
            PhotonIngressMarkerStore.mode(repository, conflictingState)
        }
    }

    @Test
    fun originCannotBePersistedAsAMarker() = runTest {
        val repository = MemoryPhotonRepository()
        assertFailsWith<IllegalArgumentException> {
            PhotonIngressMarkerStore.mark(repository, photon("origin-target"), PhotonIngressMode.ORIGIN)
        }
    }

    private fun photon(id: String): Photon = Photon(
        id = PhotonId(id),
        content = "payload:$id",
        provenance = Provenance("test", "lifeos", Instant.EPOCH),
    )
}
