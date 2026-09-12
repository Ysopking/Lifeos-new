package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotonBackedCausalLedgerStoreTest {
    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }

    @Test
    fun fullCompletedTraceIsLosslesslyRecoveredAfterStoreRecreation() = runTest {
        val repository = MemoryPhotonRepository()
        val source = Photon(
            id = PhotonId("root-full-ledger"),
            content = "Invoice 42 is due",
            tags = setOf("finance"),
            provenance = Provenance("test", "user", Instant.EPOCH),
        )
        val module = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("ledger.test", "2", "impl-v2", setOf("ledger.test")),
                acceptedMimeTypes = setOf("text/*"),
                preferredTags = setOf("finance"),
                semanticHints = setOf("invoice", "due"),
                expectedInformationGain = 0.8,
                estimatedCost = 0.1,
            ),
            processor = CognitiveModuleProcessor { _, _ ->
                CognitiveModuleResult(
                    outputPhotons = listOf(
                        Photon(
                            content = "fact=due",
                            provenance = Provenance("ledger.test", "lifeos", Instant.EPOCH),
                        )
                    ),
                    explanation = "ledger-full-test",
                )
            },
        )
        val transient = CausalCognitionEngine().process(source, listOf(module)).ledgerEntry

        PhotonBackedCausalLedgerStore(repository).append(transient)
        val recovered = PhotonBackedCausalLedgerStore(repository).load(transient.traceId)

        assertNotNull(recovered)
        assertEquals(transient, recovered)
        assertTrue(recovered.attraction.isNotEmpty())
        assertTrue(recovered.branches.isNotEmpty())
        assertTrue(recovered.processingRecords.isNotEmpty())
        assertNotNull(recovered.integration)
    }

    @Test
    fun legacyV1ReplayGuardRemainsReadable() = runTest {
        val repository = MemoryPhotonRepository()
        val traceId = CausalTraceId("legacy-trace")
        val rootId = PhotonId("legacy-root")
        val outId = PhotonId("legacy-out")
        fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        repository.save(
            Photon(
                id = PhotonBackedCausalLedgerStore.replayGuardPhotonId(traceId),
                content = listOf(
                    "v=1",
                    "trace=${encode(traceId.value)}",
                    "root=${encode(rootId.value)}",
                    "out=${encode(outId.value)}",
                ).joinToString("\n"),
                mimeType = PhotonBackedCausalLedgerStore.MIME_TYPE,
                phase = PhotonPhase.ARCHIVED,
                provenance = Provenance("causal-ledger", "lifeos", Instant.EPOCH, setOf(rootId)),
            )
        )

        val recovered = PhotonBackedCausalLedgerStore(repository).load(traceId)

        assertNotNull(recovered)
        assertEquals(rootId, recovered.rootPhotonId)
        assertEquals(listOf(outId), recovered.emittedPhotonIds)
        assertTrue(recovered.branches.isEmpty())
    }
}
