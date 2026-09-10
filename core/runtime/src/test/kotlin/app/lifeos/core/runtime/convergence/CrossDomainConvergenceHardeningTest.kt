package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrossDomainConvergenceHardeningTest {
    private val at = Instant.parse("2026-09-10T14:00:00Z")
    private val engine = FieldConvergenceEngine(
        config = ConvergenceConfig(
            maxIterations = 2,
            requiredStableRounds = 1,
            epsilon = 1.0,
            minConvergence = 0.0,
            minWinnerMargin = 0.0,
            damping = 1.0,
        )
    )

    @Test
    fun `source evidence link weight attenuates cross domain confidence`() {
        val sourceBase = fixture("weighted-source")
        val weightedHypothesis = sourceBase.hypothesis.copy(
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = sourceBase.evidence.id,
                    relation = EvidenceRelationType.SUPPORTS,
                    weight = 0.25,
                )
            )
        )
        val weightedRequest = sourceBase.request.copy(hypotheses = listOf(weightedHypothesis))
        val source = sourceBase.copy(
            hypothesis = weightedHypothesis,
            request = weightedRequest,
            input = ConvergenceDomainInput(weightedRequest),
        )
        val target = fixture("weighted-target")
        val rule = bridge(source, target).copy(confidenceMultiplier = 0.5)
        var effectiveTarget: FieldConvergenceRequest? = null
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner { request ->
                if (request.domainId == target.domain) effectiveTarget = request
                engine.converge(request)
            }
        )

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(
                domains = listOf(target.input, source.input),
                bridges = listOf(rule),
            )
        )

        assertEquals(CrossDomainConvergenceStatus.CONVERGED, result.status)
        val derived = requireNotNull(effectiveTarget)
            .evidence
            .single { it.kind == EvidenceKind.DERIVED_MEASUREMENT }
        val upperBound = source.evidence.confidence *
            source.evidence.reliability.score *
            0.25 *
            rule.confidenceMultiplier
        assertTrue(derived.confidence <= upperBound + 1e-12)
        assertEquals("SUPPORTS", derived.payload.values["sourceEvidenceRelation"])
        assertEquals(java.lang.Double.toHexString(0.25), derived.payload.values["sourceEvidenceWeight"])
    }

    @Test
    fun `duplicate source evidence is not exportable and blocks global convergence`() {
        val sourceBase = fixture("duplicate-source")
        val duplicateHypothesis = sourceBase.hypothesis.copy(
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = sourceBase.evidence.id,
                    relation = EvidenceRelationType.DUPLICATES,
                    weight = 1.0,
                )
            )
        )
        val duplicateRequest = sourceBase.request.copy(hypotheses = listOf(duplicateHypothesis))
        val source = sourceBase.copy(
            hypothesis = duplicateHypothesis,
            request = duplicateRequest,
            input = ConvergenceDomainInput(duplicateRequest),
        )
        val target = fixture("duplicate-target")

        val result = ConvergenceCoordinator(DomainConvergenceRunner(engine::converge)).coordinate(
            CrossDomainConvergenceRequest(
                domains = listOf(source.input, target.input),
                bridges = listOf(bridge(source, target)),
            )
        )

        assertEquals(CrossDomainBridgeStatus.NO_EXPORTABLE_EVIDENCE, result.bridgeTrace.single().status)
        assertEquals(CrossDomainConvergenceStatus.UNRESOLVED, result.status)
    }

    @Test
    fun `cross domain request identity changes when evidence physics changes without id change`() {
        val base = fixture("identity")
        val changedEvidence = base.evidence.copy(
            confidence = 0.51,
            reliability = EvidenceReliability(0.51, "changed-reliability"),
        )
        assertEquals(base.evidence.id, changedEvidence.id)
        val changedRequest = base.request.copy(evidence = listOf(changedEvidence))

        val original = CrossDomainConvergenceRequest(listOf(base.input))
        val changed = CrossDomainConvergenceRequest(listOf(ConvergenceDomainInput(changedRequest)))

        assertNotEquals(original.id, changed.id)
    }

    private data class Fixture(
        val domain: FieldDomainId,
        val evidence: FieldEvidence,
        val node: FieldNode,
        val hypothesis: FieldHypothesis,
        val request: FieldConvergenceRequest,
        val input: ConvergenceDomainInput,
    )

    private fun fixture(name: String): Fixture {
        val domain = StableFieldIds.domain("hardening.$name")
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = PhotonId("photon-$name"),
            sourceRevision = 1,
            kind = EvidenceKind.OBSERVATION,
            semanticKey = "$name-evidence",
            confidence = 0.95,
            reliability = EvidenceReliability(0.95, "test-reliability"),
            authority = SourceAuthority.OFFICIAL,
            observedAt = at,
            payload = EvidencePayload.text("evidence-$name"),
            explanation = "test evidence",
        )
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = "$name-node",
            baseEnergy = 0.8,
            evidenceIds = setOf(evidence.id),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "$name-hypothesis",
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(
                HypothesisEvidenceLink(evidence.id, EvidenceRelationType.SUPPORTS, 1.0)
            ),
            explanation = "test hypothesis",
        )
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domainId = domain, nodes = listOf(node)),
            evidence = listOf(evidence),
            hypotheses = listOf(hypothesis),
            context = FieldContext(
                temporal = TemporalContext(at),
                domain = DomainContext(domain),
            ),
        )
        return Fixture(
            domain = domain,
            evidence = evidence,
            node = node,
            hypothesis = hypothesis,
            request = request,
            input = ConvergenceDomainInput(request),
        )
    }

    private fun bridge(
        source: Fixture,
        target: Fixture,
    ) = CrossDomainBridgeRule(
        id = "${source.domain.value}-to-${target.domain.value}",
        sourceDomainId = source.domain,
        targetDomainId = target.domain,
        sourceHypothesisId = source.hypothesis.id,
        targetHypothesisId = target.hypothesis.id,
        targetNodeId = target.node.id,
        relation = EvidenceRelationType.SUPPORTS,
        weight = 0.6,
        confidenceMultiplier = 0.5,
        targetSemanticKey = "bridged-${target.hypothesis.semanticKey}",
    )
}
