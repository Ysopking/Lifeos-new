package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldConflict
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConvergenceCoordinatorTest {
    private val at = Instant.parse("2026-09-10T14:00:00Z")
    private val permissiveEngine = FieldConvergenceEngine(
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
    fun `domains remain isolated when no bridge exists`() {
        val first = fixture("first")
        val second = fixture("second")
        val seen = mutableListOf<FieldConvergenceRequest>()
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner { request ->
                seen += request
                permissiveEngine.converge(request)
            }
        )

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(listOf(second.input, first.input))
        )

        assertEquals(CrossDomainConvergenceStatus.CONVERGED, result.status)
        assertTrue(result.bridgeTrace.isEmpty())
        assertEquals(2, seen.size)
        seen.forEach { effective ->
            val original = if (effective.domainId == first.domain) first.request else second.request
            assertEquals(original.evidence, effective.evidence)
            assertEquals(original.hypotheses, effective.hypotheses)
            assertEquals(original.graph, effective.graph)
        }
    }

    @Test
    fun `explicit bridge derives reduced unverified target evidence without mutating sources`() {
        val source = fixture("source")
        val target = fixture("target")
        val sourceBefore = source.request.copy()
        val targetBefore = target.request.copy()
        val seen = linkedMapOf<FieldDomainId, FieldConvergenceRequest>()
        val rule = bridge(source, target)
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner { request ->
                seen[request.domainId] = request
                permissiveEngine.converge(request)
            }
        )

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(
                domains = listOf(target.input, source.input),
                bridges = listOf(rule),
            )
        )

        assertEquals(CrossDomainConvergenceStatus.CONVERGED, result.status)
        assertEquals(CrossDomainBridgeStatus.APPLIED, result.bridgeTrace.single().status)
        val effectiveTarget = seen.getValue(target.domain)
        val derived = effectiveTarget.evidence.single { it.kind == EvidenceKind.DERIVED_MEASUREMENT }
        assertEquals(target.domain, derived.domainId)
        assertEquals(source.evidence.sourcePhotonId, derived.sourcePhotonId)
        assertEquals(source.evidence.sourceRevision, derived.sourceRevision)
        assertEquals(SourceAuthority.UNVERIFIED, derived.authority)
        assertTrue(derived.confidence < source.evidence.confidence)
        assertEquals("cross-domain-bridge", derived.payload.type)
        assertEquals(rule.id, derived.payload.values["bridgeId"])
        assertTrue(derived.id in effectiveTarget.graph.node(target.node.id)!!.evidenceIds)
        assertTrue(
            effectiveTarget.hypotheses.single().evidenceLinks.any {
                it.evidenceId == derived.id && it.relation == EvidenceRelationType.SUPPORTS
            }
        )
        assertEquals(sourceBefore, source.request)
        assertEquals(targetBefore, target.request)
    }

    @Test
    fun `forbidden sensitive context scope is rejected before any domain execution`() {
        val legal = fixture(
            "legal",
            activeScopes = setOf(FieldContextScope.LEGAL_CONTEXT),
        )
        var runs = 0
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner {
                runs += 1
                permissiveEngine.converge(it)
            }
        )

        val result = coordinator.coordinate(CrossDomainConvergenceRequest(listOf(legal.input)))

        assertEquals(CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH, result.status)
        assertEquals(0, runs)
        assertTrue(result.failures.single().contains("forbidden-context-scopes:LEGAL_CONTEXT"))
    }

    @Test
    fun `sensitive context is usable only when domain boundary explicitly allows it`() {
        val legal = fixture(
            "legal-allowed",
            activeScopes = setOf(FieldContextScope.LEGAL_CONTEXT),
            allowedScopes = setOf(FieldContextScope.LEGAL_CONTEXT),
        )

        val result = ConvergenceCoordinator(DomainConvergenceRunner(permissiveEngine::converge))
            .coordinate(CrossDomainConvergenceRequest(listOf(legal.input)))

        assertEquals(CrossDomainConvergenceStatus.CONVERGED, result.status)
    }

    @Test
    fun `cross domain bridge cycles are rejected without execution`() {
        val a = fixture("cycle-a")
        val b = fixture("cycle-b")
        var runs = 0
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner {
                runs += 1
                permissiveEngine.converge(it)
            }
        )
        val aToB = bridge(a, b, "a-to-b")
        val bToA = bridge(b, a, "b-to-a")

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(
                domains = listOf(a.input, b.input),
                bridges = listOf(aToB, bToA),
            )
        )

        assertEquals(CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH, result.status)
        assertEquals(listOf("cross-domain-bridge-cycle"), result.failures)
        assertEquals(0, runs)
    }

    @Test
    fun `invalid target mapping is rejected before execution`() {
        val source = fixture("invalid-source")
        val target = fixture("invalid-target")
        val other = fixture("other")
        val invalid = bridge(source, target).copy(targetNodeId = other.node.id)
        var runs = 0

        val result = ConvergenceCoordinator(
            DomainConvergenceRunner {
                runs += 1
                permissiveEngine.converge(it)
            }
        ).coordinate(
            CrossDomainConvergenceRequest(
                domains = listOf(source.input, target.input),
                bridges = listOf(invalid),
            )
        )

        assertEquals(CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH, result.status)
        assertEquals(0, runs)
        assertTrue(result.failures.any { it.endsWith("missing-target-node") })
    }

    @Test
    fun `unresolved source prevents bridge application and keeps global result unresolved`() {
        val source = fixture("unresolved-source", evidenceConfidence = 0.2)
        val target = fixture("unresolved-target")
        val strictSource = FieldConvergenceEngine(
            config = ConvergenceConfig(
                maxIterations = 2,
                requiredStableRounds = 1,
                epsilon = 1.0,
                minConvergence = 1.0,
                minWinnerMargin = 1.0,
                damping = 1.0,
            )
        )
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner { request ->
                if (request.domainId == source.domain) {
                    strictSource.converge(request)
                } else {
                    permissiveEngine.converge(request)
                }
            }
        )

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(
                listOf(source.input, target.input),
                listOf(bridge(source, target)),
            )
        )

        assertEquals(ConvergenceStatus.UNRESOLVED, result.domainResults.first { it.state.domainId == source.domain }.status)
        assertEquals(CrossDomainBridgeStatus.SOURCE_UNRESOLVED, result.bridgeTrace.single().status)
        assertEquals(CrossDomainConvergenceStatus.UNRESOLVED, result.status)
    }

    @Test
    fun `contradicting source evidence link is never relabeled as bridge support`() {
        val sourceBase = fixture("negative-source")
        val negativeHypothesis = sourceBase.hypothesis.copy(
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = sourceBase.evidence.id,
                    relation = EvidenceRelationType.CONTRADICTS,
                    weight = 1.0,
                )
            )
        )
        val source = sourceBase.copy(
            request = sourceBase.request.copy(hypotheses = listOf(negativeHypothesis)),
            hypothesis = negativeHypothesis,
            input = ConvergenceDomainInput(
                sourceBase.request.copy(hypotheses = listOf(negativeHypothesis)),
                sourceBase.input.boundary,
            ),
        )
        val target = fixture("negative-target")

        val result = ConvergenceCoordinator(DomainConvergenceRunner(permissiveEngine::converge))
            .coordinate(
                CrossDomainConvergenceRequest(
                    listOf(source.input, target.input),
                    listOf(bridge(source, target)),
                )
            )

        assertEquals(CrossDomainBridgeStatus.NO_EXPORTABLE_EVIDENCE, result.bridgeTrace.single().status)
        assertEquals(CrossDomainConvergenceStatus.UNRESOLVED, result.status)
        val targetResult = result.domainResults.first { it.state.domainId == target.domain }
        assertFalse(targetResult.snapshot.hypotheses.any { snapshot ->
            snapshot.id == target.hypothesis.id && snapshot.score.evidence > target.evidence.confidence
        })
    }

    @Test
    fun `domain conflict is preserved and blocks cross domain convergence`() {
        val base = fixture("conflicted")
        val secondNode = FieldNode.create(
            domainId = base.domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = "conflicted-other",
            baseEnergy = 0.2,
        )
        val conflict = FieldConflict(
            key = "explicit-conflict",
            nodeIds = setOf(base.node.id, secondNode.id),
            severity = 0.8,
            evidenceIds = setOf(base.evidence.id),
            explanation = "conflicting domain claims",
        )
        val request = base.request.copy(
            graph = base.request.graph.copy(
                nodes = base.request.graph.nodes + secondNode,
                conflicts = listOf(conflict),
            )
        )
        val input = ConvergenceDomainInput(request, base.input.boundary)

        val result = ConvergenceCoordinator(DomainConvergenceRunner(permissiveEngine::converge))
            .coordinate(CrossDomainConvergenceRequest(listOf(input)))

        assertEquals(CrossDomainConvergenceStatus.UNRESOLVED, result.status)
        assertEquals("explicit-conflict", result.conflicts.single().conflictKey)
        assertEquals(0.8, result.conflicts.single().severity)
    }

    @Test
    fun `domain failure is contained with prior deterministic results retained`() {
        val first = fixture("failure-first")
        val second = fixture("failure-second")
        val ordered = listOf(first, second).sortedBy { it.domain.value }
        val failingDomain = ordered.last().domain
        val coordinator = ConvergenceCoordinator(
            DomainConvergenceRunner { request ->
                if (request.domainId == failingDomain) error("domain exploded")
                permissiveEngine.converge(request)
            }
        )

        val result = coordinator.coordinate(
            CrossDomainConvergenceRequest(listOf(second.input, first.input))
        )

        assertEquals(CrossDomainConvergenceStatus.DOMAIN_FAILURE, result.status)
        assertEquals(1, result.domainResults.size)
        assertTrue(result.failures.single().contains("domain exploded"))
    }

    @Test
    fun `domain input and bridge order do not change request identity or result`() {
        val source = fixture("det-source")
        val target = fixture("det-target")
        val third = fixture("det-third")
        val sourceToTarget = bridge(source, target, "bridge-z")
        val sourceToThird = bridge(source, third, "bridge-a")
        val leftRequest = CrossDomainConvergenceRequest(
            domains = listOf(third.input, target.input, source.input),
            bridges = listOf(sourceToTarget, sourceToThird),
        )
        val rightRequest = CrossDomainConvergenceRequest(
            domains = listOf(source.input, target.input, third.input),
            bridges = listOf(sourceToThird, sourceToTarget),
        )
        val coordinator = ConvergenceCoordinator(DomainConvergenceRunner(permissiveEngine::converge))

        val left = coordinator.coordinate(leftRequest)
        val right = coordinator.coordinate(rightRequest)

        assertEquals(leftRequest.id, rightRequest.id)
        assertEquals(left.status, right.status)
        assertEquals(left.domainResults.map { it.snapshot.id }, right.domainResults.map { it.snapshot.id })
        assertEquals(left.bridgeTrace, right.bridgeTrace)
        assertEquals(left.conflicts, right.conflicts)
    }

    private data class Fixture(
        val domain: FieldDomainId,
        val evidence: FieldEvidence,
        val node: FieldNode,
        val hypothesis: FieldHypothesis,
        val request: FieldConvergenceRequest,
        val input: ConvergenceDomainInput,
    )

    private fun fixture(
        name: String,
        evidenceConfidence: Double = 0.95,
        activeScopes: Set<FieldContextScope> = setOf(FieldContextScope.CURRENT_TASK),
        allowedScopes: Set<FieldContextScope> = ConvergenceDomainBoundary.DEFAULT_SCOPES,
    ): Fixture {
        val domain = StableFieldIds.domain("test.$name")
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = PhotonId("photon-$name"),
            sourceRevision = 1,
            kind = EvidenceKind.OBSERVATION,
            semanticKey = "$name-evidence",
            confidence = evidenceConfidence,
            reliability = EvidenceReliability(evidenceConfidence, "test-reliability"),
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
                activeScopes = activeScopes,
            ),
        )
        val input = ConvergenceDomainInput(
            request = request,
            boundary = ConvergenceDomainBoundary(domain, allowedScopes),
        )
        return Fixture(domain, evidence, node, hypothesis, request, input)
    }

    private fun bridge(
        source: Fixture,
        target: Fixture,
        id: String = "source-to-target",
    ) = CrossDomainBridgeRule(
        id = id,
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
