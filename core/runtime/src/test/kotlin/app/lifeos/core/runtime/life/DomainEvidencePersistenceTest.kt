package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainEvidencePersistenceTest {
    @Test
    fun legacyStructuredEvidenceConvergesThroughAuthoritativeSourceState() = runTest {
        val repository = MemoryPhotonRepository()
        val source = source("source-a", setOf("evidence:supports"))
        repository.save(source)
        val evidence = DomainCognitionModules.legal().processor.process(source, context()).outputPhotons.first()
        repository.save(evidence)

        val convergence = DomainEvidenceConvergenceCoordinator(repository).convergePersisted(evidence)

        assertNotNull(convergence)
        assertTrue(convergence.id.value.startsWith("domain-convergence-"))
        assertTrue(convergence.content.contains("status=CONFIRMED"))
        assertEquals(setOf(source.id), convergence.provenance.parentIds)
    }

    @Test
    fun supportAndContradictionRemainParallelAndConvergeUnresolved() = runTest {
        val repository = MemoryPhotonRepository()
        val supportSource = source("source-support", setOf("evidence:supports"))
        val contradictionSource = source("source-contradiction", setOf("evidence:contradicts"))
        repository.save(supportSource)
        repository.save(contradictionSource)
        val module = DomainCognitionModules.legal()
        val support = module.processor.process(supportSource, context()).outputPhotons.first()
        val contradiction = module.processor.process(contradictionSource, context()).outputPhotons.first()
        repository.save(support)
        repository.save(contradiction)

        val convergence = DomainEvidenceConvergenceCoordinator(repository).convergePersisted(contradiction)

        assertNotNull(convergence)
        assertTrue(convergence.content.contains("status=UNRESOLVED"))
        assertEquals(setOf(supportSource.id, contradictionSource.id), convergence.provenance.parentIds)
    }

    @Test
    fun existingCanonicalConvergenceIsAnIdempotentReplay() = runTest {
        val repository = MemoryPhotonRepository()
        val source = source("source-idempotent", setOf("evidence:supports"))
        repository.save(source)
        val evidence = DomainCognitionModules.legal().processor.process(source, context()).outputPhotons.first()
        repository.save(evidence)
        val coordinator = DomainEvidenceConvergenceCoordinator(repository)
        val first = assertNotNull(coordinator.convergePersisted(evidence))
        repository.save(first)

        assertNull(coordinator.convergePersisted(evidence))
    }

    @Test
    fun changedSourceStateFailsClosedForLegacyEvidence() = runTest {
        val repository = MemoryPhotonRepository()
        val original = source("source-conflict", setOf("evidence:supports"))
        val evidence = DomainCognitionModules.legal().processor.process(original, context()).outputPhotons.first()
        repository.save(original.copy(content = "Different immutable state"))
        repository.save(evidence)

        assertFailsWith<IllegalArgumentException> {
            DomainEvidenceConvergenceCoordinator(repository).convergePersisted(evidence)
        }
    }

    @Test
    fun persistenceWrapperStoresEvidenceBeforeItsConvergencePhoton() = runTest {
        val repository = MemoryPhotonRepository()
        val source = source("source-wrapper", setOf("evidence:supports"))
        repository.save(source)
        val evidence = DomainCognitionModules.legal().processor.process(source, context()).outputPhotons.first()
        val order = mutableListOf<PhotonId>()
        val delegate = CausalDerivedPhotonPersistence { photon, _ ->
            order += photon.id
            repository.save(photon)
        }
        val persistence = DomainEvidenceConvergingPersistence(
            delegate = delegate,
            convergence = DomainEvidenceConvergenceCoordinator(repository),
        )

        persistence.persist(evidence, CausalTraceId("trace:test"))

        assertEquals(2, order.size)
        assertEquals(evidence.id, order.first())
        assertTrue(order.last().value.startsWith("domain-convergence-"))
        assertNotNull(repository.load(order.last()))
    }

    private fun source(id: String, tags: Set<String>): Photon = Photon(
        id = PhotonId(id),
        content = "Mahnung ueber 100 EUR",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse("2026-09-12T12:00:00Z"),
        ),
        tags = tags,
    )

    private fun context(): app.lifeos.core.model.DeterminismContext {
        val state = app.lifeos.core.model.StableCognitiveIds.stateHash("domain-persistence-test")
        return app.lifeos.core.model.DeterminismContext(
            traceId = CausalTraceId("trace:domain-persistence-test"),
            logicalTick = app.lifeos.core.model.LogicalTick(1),
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
