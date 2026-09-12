package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.LogicalTick
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DomainEvidenceConvergenceTest {
    @Test
    fun propositionIsStableWhileSourceRevisionChangesInterpretationIdentity() {
        val fact = DomainFact(
            kind = DomainFactKind.CLAIM,
            value = "Rechnung 100 EUR",
            confidence = 0.9,
            evidence = "Rechnung 100 EUR",
        )
        val producer = producer(version = "3")
        val first = DomainEvidenceIdentity.assertion(photon("a", 1, "Rechnung 100 EUR"), fact, producer)
        val secondSource = DomainEvidenceIdentity.assertion(photon("b", 1, "Rechnung 100 EUR"), fact, producer)
        val revised = DomainEvidenceIdentity.assertion(photon("a", 2, "Rechnung 100 EUR"), fact, producer)

        assertEquals(first.factId, secondSource.factId)
        assertEquals(first.factId, revised.factId)
        assertNotEquals(first.evidenceFingerprint, secondSource.evidenceFingerprint)
        assertNotEquals(first.evidenceFingerprint, revised.evidenceFingerprint)
        assertNotEquals(first.interpretationId, revised.interpretationId)
        assertNotEquals(first.sourceStateHash, revised.sourceStateHash)
    }

    @Test
    fun producerVersionChangesInterpretationWithoutChangingSourceEvidenceIdentity() {
        val source = photon("same-source", 1, "Rechnung 100 EUR")
        val fact = DomainFact(DomainFactKind.CLAIM, "Rechnung 100 EUR", 0.9, "Rechnung 100 EUR")
        val v3 = DomainEvidenceIdentity.assertion(source, fact, producer(version = "3"))
        val v4 = DomainEvidenceIdentity.assertion(source, fact, producer(version = "4"))

        assertEquals(v3.factId, v4.factId)
        assertEquals(v3.evidenceFingerprint, v4.evidenceFingerprint)
        assertNotEquals(v3.interpretationId, v4.interpretationId)
        assertNotEquals(v3.producerModuleFingerprint, v4.producerModuleFingerprint)
    }

    @Test
    fun conflictingReuseOfInterpretationIdentityFailsClosed() {
        val assertion = DomainEvidenceIdentity.assertion(
            photon("conflict", 1, "Rechnung 100 EUR"),
            DomainFact(DomainFactKind.CLAIM, "Rechnung 100 EUR", 0.9, "Rechnung 100 EUR"),
            producer(),
        )

        assertFailsWith<IllegalArgumentException> {
            DomainEvidenceConvergenceEngine().converge(
                listOf(assertion, assertion.copy(stance = DomainEvidenceStance.CONTRADICTS)),
            )
        }
    }

    @Test
    fun contradictoryParallelEvidenceRemainsUnresolvedAndOrderIndependent() {
        val fact = DomainFact(DomainFactKind.CLAIM, "Rechnung 100 EUR", 0.9, "Rechnung 100 EUR")
        val producer = producer()
        val support = DomainEvidenceIdentity.assertion(
            photon("support", 1, "Rechnung 100 EUR", setOf("evidence:supports")),
            fact,
            producer,
        )
        val contradiction = DomainEvidenceIdentity.assertion(
            photon("contradict", 1, "Rechnung 100 EUR", setOf("evidence:contradicts")),
            fact.copy(confidence = 0.85),
            producer,
        )
        val engine = DomainEvidenceConvergenceEngine()

        val forward = engine.converge(listOf(support, contradiction))
        val reverse = engine.converge(listOf(contradiction, support))

        assertEquals(DomainEvidenceConvergenceStatus.UNRESOLVED, forward.status)
        assertEquals(forward, reverse)
        assertEquals(2, forward.evidenceFingerprints.size)
        assertEquals(2, forward.interpretationIds.size)
    }

    @Test
    fun unresolvedConvergenceBecomesAReflectingPhotonWithCompleteParentLineage() {
        val fact = DomainFact(DomainFactKind.CLAIM, "Rechnung 100 EUR", 0.9, "Rechnung 100 EUR")
        val producer = producer()
        val support = DomainEvidenceIdentity.assertion(
            photon("support-parent", 1, "Rechnung 100 EUR", setOf("evidence:supports")),
            fact,
            producer,
        )
        val contradiction = DomainEvidenceIdentity.assertion(
            photon("contradict-parent", 1, "Rechnung 100 EUR", setOf("evidence:contradicts")),
            fact.copy(confidence = 0.9),
            producer,
        )
        val convergence = DomainEvidenceConvergenceEngine().converge(listOf(support, contradiction))

        val photon = DomainEvidenceConvergencePhotonFactory.create(
            convergence,
            listOf(contradiction, support),
        )

        assertEquals(PhotonPhase.REFLECTING, photon.phase)
        assertEquals(
            setOf(PhotonId("support-parent"), PhotonId("contradict-parent")),
            photon.provenance.parentIds,
        )
        assertTrue(photon.id.value.startsWith("domain-convergence-"))
        assertTrue(photon.content.contains("status=UNRESOLVED"))
        assertEquals(2, photon.relations.size)
        assertTrue(photon.relations.all { it.type == RelationType.REFERENCES })
    }

    @Test
    fun decisiveSupportCanConvergeWithoutDiscardingContradiction() {
        val fact = DomainFact(DomainFactKind.PAYMENT, "100 EUR", 0.95, "100 EUR")
        val producer = producer()
        val support = DomainEvidenceIdentity.assertion(
            photon("support", 1, "100 EUR", setOf("evidence:supports")),
            fact,
            producer,
        )
        val contradiction = DomainEvidenceIdentity.assertion(
            photon("weak-contradiction", 1, "100 EUR", setOf("evidence:contradicts")),
            fact.copy(confidence = 0.4),
            producer,
        )

        val result = DomainEvidenceConvergenceEngine().converge(listOf(support, contradiction))

        assertEquals(DomainEvidenceConvergenceStatus.CONFIRMED, result.status)
        assertEquals(0.95, result.supportConfidence)
        assertEquals(0.4, result.contradictionConfidence)
        assertEquals(2, result.evidenceFingerprints.size)
    }

    @Test
    fun evidenceAboveBudgetCannotSilentlyCreateCertainty() {
        val fact = DomainFact(DomainFactKind.KPI, "Marge 20 Prozent", 0.99, "Marge 20 Prozent")
        val producer = producer()
        val assertions = (1..3).map { index ->
            DomainEvidenceIdentity.assertion(
                photon("source-$index", 1, "Marge 20 Prozent", setOf("evidence:supports")),
                fact,
                producer,
            )
        }

        val result = DomainEvidenceConvergenceEngine(maxEvidence = 2).converge(assertions)

        assertTrue(result.boundedOut)
        assertEquals(3, result.totalEvidenceCount)
        assertEquals(2, result.evidenceFingerprints.size)
        assertEquals(DomainEvidenceConvergenceStatus.UNRESOLVED, result.status)
    }

    @Test
    fun domainModuleEmitsVersionedEvidenceMetadataAndTypedRelation() = runTest {
        val source = photon(
            id = "legal-source",
            revision = 1,
            content = "Mahnung über 100 EUR, fällig 12.09.2026",
            tags = setOf("evidence:contradicts"),
        )
        val module = DomainCognitionModules.legal()
        val result = module.processor.process(source, context())

        assertEquals("3", module.descriptor.identity.version)
        assertTrue(result.outputPhotons.isNotEmpty())
        result.outputPhotons.forEach { output ->
            assertTrue(output.content.contains("fact_id=fact:"))
            assertTrue(output.content.contains("interpretation_id=fact-interpretation:"))
            assertTrue(output.content.contains("evidence_fingerprint="))
            assertTrue(output.content.contains("source_state_hash="))
            assertTrue(output.content.contains("producer_version=3"))
            assertTrue(output.content.contains("stance=CONTRADICTS"))
            assertTrue(source.id in output.provenance.parentIds)
            assertTrue(output.relations.any { it.target == source.id && it.type == RelationType.CONTRADICTS })
        }
    }

    private fun producer(version: String = "3"): ModuleIdentity = ModuleIdentity(
        moduleId = "domain.test",
        version = version,
        implementationHash = "domain-test-$version",
        capabilityIds = setOf("domain.analyze.test"),
    )

    private fun photon(
        id: String,
        revision: Long,
        content: String,
        tags: Set<String> = emptySet(),
    ): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse("2026-09-12T10:00:00Z"),
        ),
        tags = tags,
    )

    private fun context(): DeterminismContext {
        val state = StableCognitiveIds.stateHash("domain-evidence-test")
        return DeterminismContext(
            traceId = CausalTraceId("trace:domain-evidence-test"),
            logicalTick = LogicalTick(1),
            inputHash = state,
            parentStateHash = state,
            runtimeVersion = "test",
            policyVersion = "test",
            randomSeed = 1L,
            parametersHash = state,
        )
    }
}
