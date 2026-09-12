package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.LogicalTick
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class DomainEvidenceContainmentTest {
    @Test
    fun malformedUnrelatedFactDoesNotBlockCurrentFactConvergence() = runTest {
        val repository = MemoryPhotonRepository()
        val source = Photon(
            id = PhotonId("source-contained"),
            content = "Mahnung ueber 100 EUR",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = Instant.parse("2026-09-12T12:00:00Z"),
            ),
            tags = setOf("evidence:supports"),
        )
        repository.save(source)
        val evidence = DomainCognitionModules.legal().processor.process(source, context()).outputPhotons.first()
        repository.save(evidence)
        repository.save(
            Photon(
                id = PhotonId("unrelated-corrupt-evidence"),
                content = "malformed",
                mimeType = DomainEvidencePhotonCodec.MIME_TYPE,
                provenance = Provenance(
                    source = "test",
                    actor = "test",
                    createdAt = Instant.parse("2026-09-12T12:00:00Z"),
                ),
                tags = setOf(
                    "structured-domain-evidence",
                    "fact-id:fact:unrelated",
                ),
            )
        )

        val convergence = DomainEvidenceConvergenceCoordinator(repository).convergePersisted(evidence)

        assertNotNull(convergence)
    }

    private fun context(): DeterminismContext {
        val state = StableCognitiveIds.stateHash("domain-containment-test")
        return DeterminismContext(
            traceId = CausalTraceId("trace:domain-containment-test"),
            logicalTick = LogicalTick(1),
            inputHash = state,
            parentStateHash = state,
            runtimeVersion = "test",
            policyVersion = "test",
            randomSeed = 1L,
            parametersHash = state,
        )
    }

    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }
}
