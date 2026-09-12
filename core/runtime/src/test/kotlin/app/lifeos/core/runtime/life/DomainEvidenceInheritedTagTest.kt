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

class DomainEvidenceInheritedTagTest {
    @Test
    fun inheritedFactTagFromAnotherAssertionDoesNotJoinCurrentFact() = runTest {
        val repository = MemoryPhotonRepository()
        val firstSource = source("source-first", "Mahnung ueber 100 EUR")
        val secondSource = source("source-second", "Vertrag mit Frist 12.09.2026")
        repository.save(firstSource)
        repository.save(secondSource)
        val module = DomainCognitionModules.legal()
        val first = module.processor.process(firstSource, context()).outputPhotons.first()
        val secondBase = module.processor.process(secondSource, context()).outputPhotons.first()
        val inheritedFactTag = first.tags.single { it.startsWith("fact-id:") }
        val second = secondBase.copy(tags = secondBase.tags + inheritedFactTag)
        repository.save(first)
        repository.save(second)

        val convergence = DomainEvidenceConvergenceCoordinator(repository).convergePersisted(first)

        assertNotNull(convergence)
        kotlin.test.assertEquals(setOf(firstSource.id), convergence.provenance.parentIds)
    }

    private fun source(id: String, content: String): Photon = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse("2026-09-12T12:00:00Z"),
        ),
        tags = setOf("evidence:supports"),
    )

    private fun context(): DeterminismContext {
        val state = StableCognitiveIds.stateHash("domain-inherited-tag-test")
        return DeterminismContext(
            traceId = CausalTraceId("trace:domain-inherited-tag-test"),
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
