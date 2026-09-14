package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.PhotonIngressMarkerStore
import app.lifeos.core.runtime.PhotonIngressMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ProductivePhotonIngressClassificationTest {
    @Test
    fun `derived classification is durable idempotent and cannot become origin`() = runTest {
        val repository = RecordingPhotonRepository()
        val photon = photon(revision = 1L)

        ProductivePhotonIngressClassification.requireOrMark(repository, photon, PhotonIngressMode.DERIVED)
        ProductivePhotonIngressClassification.requireOrMark(repository, photon, PhotonIngressMode.DERIVED)

        assertEquals(PhotonIngressMode.DERIVED, PhotonIngressMarkerStore.mode(repository, photon))
        assertFailsWith<IllegalStateException> {
            ProductivePhotonIngressClassification.requireOrMark(repository, photon, PhotonIngressMode.ORIGIN)
        }
    }

    @Test
    fun `classification is revision scoped and modes cannot mutate`() = runTest {
        val repository = RecordingPhotonRepository()
        val first = photon(revision = 1L)
        val second = photon(revision = 2L)

        ProductivePhotonIngressClassification.requireOrMark(repository, first, PhotonIngressMode.DERIVED)
        ProductivePhotonIngressClassification.requireOrMark(repository, second, PhotonIngressMode.REPLAY)

        assertEquals(PhotonIngressMode.DERIVED, PhotonIngressMarkerStore.mode(repository, first))
        assertEquals(PhotonIngressMode.REPLAY, PhotonIngressMarkerStore.mode(repository, second))
        assertFailsWith<IllegalStateException> {
            ProductivePhotonIngressClassification.requireOrMark(repository, first, PhotonIngressMode.REPLAY)
        }
    }

    @Test
    fun `origin remains marker free`() = runTest {
        val repository = RecordingPhotonRepository()
        val photon = photon(revision = 1L)

        ProductivePhotonIngressClassification.requireOrMark(repository, photon, PhotonIngressMode.ORIGIN)

        assertEquals(PhotonIngressMode.ORIGIN, PhotonIngressMarkerStore.mode(repository, photon))
    }

    private fun photon(revision: Long): Photon = Photon(
        id = PhotonId("producer-audit-photon"),
        revision = revision,
        content = "revision-$revision",
        provenance = Provenance(
            source = "producer-audit-test",
            actor = "test",
            createdAt = Instant.parse("2026-09-13T01:00:00Z").plusSeconds(revision),
        ),
    )

    private class RecordingPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = values.values.toList(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
