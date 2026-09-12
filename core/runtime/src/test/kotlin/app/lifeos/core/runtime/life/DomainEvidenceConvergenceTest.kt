package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.LogicalTick
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DomainEvidenceConvergenceTest {
    @Test
    fun factIdentityIsStableWhileEvidenceFingerprintTracksSourceRevision() {
        val fact = DomainFact(
            kind = DomainFactKind.CLAIM,
            value = "Rechnung 100 EUR",
            confidence = 0.9,
            evidence = "Rechnung 100 EUR",
        )
        val first = DomainEvidenceIdentity.assertion(photon("a", 1, "Rechnung 100 EUR"), fact)
        val second = DomainEvidenceIdentity.assertion(photon("b", 1, "Rechnung 100 EUR"), fact)
        val revised = DomainEvidenceIdentity.assertion(photon("a", 2, "Rechnung 100 EUR"), fact)

        assertEquals(first.factId, second.factId)
        assertEquals(first.factId, revised.factId)
        assertNotEquals(first.evidenceFingerprint, second.evidenceFingerprint)
        assertNotEquals(first.evidenceFingerprint, revised.evidenceFingerprint)
    }

    @Test
    fun contradictoryParallelEvidenceRemainsUnresolvedAndOrderIndependent() {
        val fact = DomainFact(DomainFactKind.CLAIM, "Rechnung 100 EUR", 0.9, "Rechnung 100 EUR")
        val support = DomainEvidenceIdentity.assertion(
            photon("support", 1, "Rechnung 100 EUR", setOf("evidence:supports")),
            fact,
        )
        val contradiction = DomainEvidenceIdentity.assertion(
            photon("contradict", 1, "Rechnung 100 EUR", setOf("evidence:contradicts")),
            fact.copy(confidence = 0.85),
        )
        val engine = DomainEvidenceConvergenceEngine()

        val forward = engine.converge(listOf(support, contradiction))
        val reverse = engine.converge(listOf(contradiction, support))

        assertEquals(DomainEvidenceConvergenceStatus.UNRESOLVED, forward.status)
        assertEquals(forward, reverse)
        assertEquals(2, forward.evidenceFingerprints.size)
    }

    @Test
    fun decisiveSupportCanConvergeWithoutDiscardingContradiction() {
        val fact = DomainFact(DomainFactKind.PAYMENT, "100 EUR", 0.95, "100 EUR")
        val support = DomainEvidenceIdentity.assertion(
            photon("support", 1, "100 EUR", setOf("evidence:supports")),
            fact,
        )
        val contradiction = DomainEvidenceIdentity.assertion(
            photon("weak-contradiction", 1, "100 EUR", setOf("evidence:contradicts")),
            fact.copy(confidence = 0.4),
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
        val assertions = (1..3).map { index ->
            DomainEvidenceIdentity.assertion(
                photon("source-$index", 1, "Marge 20 Prozent", setOf("evidence:supports")),
                fact,
            )
        }

        val result = DomainEvidenceConvergenceEngine(maxEvidence = 2).converge(assertions)

        assertTrue(result.boundedOut)
        assertEquals(3, result.totalEvidenceCount)
        assertEquals(2, result.evidenceFingerprints.size)
        assertEquals(DomainEvidenceConvergenceStatus.UNRESOLVED, result.status)
    }

    @Test
    fun domainModuleEmitsStableEvidenceMetadataAndTypedRelation() = runTest {
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
            assertTrue(output.content.contains("evidence_fingerprint="))
            assertTrue(output.content.contains("stance=CONTRADICTS"))
            assertTrue(source.id in output.provenance.parentIds)
            assertTrue(output.relations.any { it.target == source.id && it.type == RelationType.CONTRADICTS })
        }
    }

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
