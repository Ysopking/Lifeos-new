package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.life.DurableLifeSourceIngestor
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.life.LifeSourceRecord
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalLifePhotonRepositoryTest {
    private class MemoryPhotonRepository : PhotonRepository {
        val data = linkedMapOf<PhotonId, Photon>()
        var saves = 0

        override suspend fun save(photon: Photon) {
            saves += 1
            data[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = data[id]

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = data.values.toList()

        override suspend fun delete(id: PhotonId) {
            data.remove(id)
        }
    }

    @Test
    fun productiveLifePhotonsUseCanonicalModesWhileCheckpointStaysPersistOnly() = runBlocking {
        val delegate = MemoryPhotonRepository()
        val ingress = mutableListOf<Pair<PhotonId, PhotonIngressMode>>()
        val repository = CanonicalLifePhotonRepository(delegate) { photon, mode ->
            ingress += photon.id to mode
            delegate.save(photon)
        }

        repository.save(photon("evidence", setOf("life-source-evidence")))
        repository.save(photon("gap", setOf("life-source-gap")))
        repository.save(photon("atom", setOf("life-memory-management", "memory-atom")))
        repository.save(photon("crystal", setOf("life-memory-management", "memory-crystal")))
        repository.save(photon("report", setOf("life-memory-management", "initial-data-bootstrap")))
        repository.save(photon("checkpoint", setOf("life-memory-management", "life-source-checkpoint")))

        assertEquals(
            listOf(
                PhotonId("evidence") to PhotonIngressMode.ORIGIN,
                PhotonId("gap") to PhotonIngressMode.ORIGIN,
                PhotonId("atom") to PhotonIngressMode.DERIVED,
                PhotonId("crystal") to PhotonIngressMode.DERIVED,
                PhotonId("report") to PhotonIngressMode.DERIVED,
            ),
            ingress,
        )
        assertEquals(6, delegate.saves)
    }

    @Test
    fun reconcilePersistedReplaysOnlyProductiveLifePhotons() = runBlocking {
        val delegate = MemoryPhotonRepository()
        val evidence = photon("evidence", setOf("life-source-evidence"))
        val memory = photon("memory", setOf("life-memory-management", "memory-atom"))
        val checkpoint = photon("checkpoint", setOf("life-memory-management", "life-source-checkpoint"))
        delegate.save(evidence)
        delegate.save(memory)
        delegate.save(checkpoint)

        val ingress = mutableListOf<Pair<PhotonId, PhotonIngressMode>>()
        val repository = CanonicalLifePhotonRepository(delegate) { photon, mode ->
            ingress += photon.id to mode
        }

        assertEquals(2, repository.reconcilePersisted())
        assertEquals(
            listOf(
                evidence.id to PhotonIngressMode.ORIGIN,
                memory.id to PhotonIngressMode.DERIVED,
            ),
            ingress,
        )
    }

    @Test
    fun crashAfterEvidencePersistenceDoesNotAdvanceCursorAndReconcileMakesRetrySafe() = runBlocking {
        val delegate = MemoryPhotonRepository()
        var failAfterPersistence = true
        val ingress = mutableListOf<Pair<PhotonId, PhotonIngressMode>>()
        val repository = CanonicalLifePhotonRepository(delegate) { photon, mode ->
            delegate.save(photon)
            if (failAfterPersistence && "life-source-evidence" in photon.tags) {
                throw IllegalStateException("simulated crash after Photon persistence")
            }
            ingress += photon.id to mode
        }
        val descriptor = LifeSourceDescriptor("calendar", "adapter-v4")
        val record = LifeSourceRecord(
            sourceId = "calendar",
            recordId = "event-1",
            observedAt = Instant.parse("2026-09-12T12:00:00Z"),
            payload = "Important meeting",
        )

        assertFailsWith<IllegalStateException> {
            DurableLifeSourceIngestor(repository).ingest(
                descriptor = descriptor,
                records = listOf(record),
                nextPosition = "cursor-1",
                authorized = true,
                committedAt = Instant.parse("2026-09-12T12:01:00Z"),
            )
        }
        assertTrue(delegate.loadAll().any { "life-source-evidence" in it.tags })
        assertNull(delegate.loadAll().singleOrNull { "life-source-checkpoint" in it.tags })

        failAfterPersistence = false
        assertEquals(1, repository.reconcilePersisted())

        val recovered = DurableLifeSourceIngestor(repository).ingest(
            descriptor = descriptor,
            records = listOf(record),
            nextPosition = "cursor-1",
            authorized = true,
            committedAt = Instant.parse("2026-09-12T12:02:00Z"),
        )

        assertEquals("cursor-1", recovered.cursorAfter.position)
        assertEquals(1, delegate.loadAll().count { "life-source-evidence" in it.tags })
        assertEquals(1, delegate.loadAll().count { "life-source-checkpoint" in it.tags })
        assertEquals(PhotonIngressMode.ORIGIN, ingress.single().second)
    }

    private fun photon(id: String, tags: Set<String>): Photon = Photon(
        id = PhotonId(id),
        content = "payload:$id",
        provenance = Provenance(
            source = "test",
            actor = "lifeos",
            createdAt = Instant.EPOCH,
        ),
        tags = tags,
    )
}
