package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PhotonBackedCausalLedgerStoreTest {
    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
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
    fun completedTraceIsRecoveredAfterStoreRecreation() = runTest {
        val repository = MemoryPhotonRepository()
        val traceId = CausalTraceId("a".repeat(64))
        val entry = CausalLedgerEntry(
            traceId = traceId,
            rootPhotonId = PhotonId("root-1"),
            attraction = emptyList(),
            branches = emptyList(),
            processingRecords = emptyList(),
            integration = null,
            emittedPhotonIds = listOf(PhotonId("derived-1"), PhotonId("integration-1")),
        )

        PhotonBackedCausalLedgerStore(repository).append(entry)
        val recovered = PhotonBackedCausalLedgerStore(repository).load(traceId)

        assertNotNull(recovered)
        assertEquals(entry.traceId, recovered.traceId)
        assertEquals(entry.rootPhotonId, recovered.rootPhotonId)
        assertEquals(entry.emittedPhotonIds.sortedBy { it.value }, recovered.emittedPhotonIds.sortedBy { it.value })
        assertEquals(
            PhotonId("causal-${traceId.value}"),
            PhotonBackedCausalLedgerStore.replayGuardPhotonId(traceId),
        )
    }
}
