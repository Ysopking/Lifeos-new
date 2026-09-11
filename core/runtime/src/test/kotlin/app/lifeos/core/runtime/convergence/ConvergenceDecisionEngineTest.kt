package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldConflict
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldConvergenceRequest
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
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ConvergenceDecisionEngineTest {
    private val now = Instant.parse("2026-09-11T08:00:00Z")
    private val fieldEngine = FieldConvergenceEngine(
        config = ConvergenceConfig(
            maxIterations = 2,
            requiredStableRounds = 1,
            epsilon = 1.0,
            minConvergence = 0.0,
            minWinnerMargin = 0.08,
            damping = 1.0,
        )
    )

    @Test
    fun `single strongly supported fresh hypothesis becomes actionable`() {
        val fixture = fixture("action", hypothesisCount = 1)
        val request = decisionRequest(fixture)

        val decision = ConvergenceDecisionEngine(
            policy = ConvergenceDecisionPolicy(minTotalScore = 0.65)
        ).decide(request)

        assertEquals(ConvergenceDecisionState.ACTIONABLE, decision.state)
        assertEquals(listOf(fixture.hypotheses.single().id), decision.selectedHypothesisIds)
        assertTrue(decision.evidenceRequests.isEmpty())
        assertTrue(decision.capabilityGaps.isEmpty())
        assertNull(decision.escalation)
    }

    @Test
    fun `exact tie remains evidence required and deterministic instead of using stable id as winner`() {
        val fixture = fixture("tie", hypothesisCount = 2)
        val request = decisionRequest(fixture)
        val engine = ConvergenceDecisionEngine(
            policy = ConvergenceDecisionPolicy(minTotalScore = 0.0, minEvidenceScore = 0.0)
        )

        val first = engine.decide(request)
        val second = engine.decide(request)

        assertEquals(ConvergenceDecisionState.EVIDENCE_REQUIRED, first.state)
        assertTrue(first.selectedHypothesisIds.isEmpty())
        assertTrue(first.evidenceRequests.any { it.kind == ConvergenceEvidenceGapKind.INSUFFICIENT_MARGIN })
        assertEquals(ConvergenceEscalationTarget.DEEP_SEARCH, first.escalation?.target)
        assertEquals(first.id, second.id)
        assertEquals(first.evidenceRequests.map { it.id }, second.evidenceRequests.map { it.id })
    }

    @Test
    fun `stale supporting evidence requests freshness instead of action`() {
        val fixture = fixture(
            name = "stale",
            hypothesisCount = 1,
            observedAt = now.minus(Duration.ofDays(30)),
        )
        val policy = ConvergenceDecisionPolicy(
            minTotalScore = 0.0,
            minEvidenceScore = 0.0,
            maxEvidenceAge = Duration.ofDays(1),
        )

        val decision = ConvergenceDecisionEngine(policy).decide(decisionRequest(fixture))

        assertEquals(ConvergenceDecisionState.EVIDENCE_REQUIRED, decision.state)
        assertTrue(decision.evidenceRequests.any { it.kind == ConvergenceEvidenceGapKind.STALE_EVIDENCE })
        assertEquals(ConvergenceEscalationTarget.DEEP_SEARCH, decision.escalation?.target)
    }

    @Test
    fun `severe field conflict blocks action and stays first class`() {
        val fixture = fixture("conflict", hypothesisCount = 2, conflictSeverity = 0.9)
        val decision = ConvergenceDecisionEngine(
            ConvergenceDecisionPolicy(minTotalScore = 0.0, minEvidenceScore = 0.0)
        ).decide(decisionRequest(fixture))

        assertEquals(ConvergenceDecisionState.CONFLICTED, decision.state)
        assertTrue(decision.selectedHypothesisIds.isEmpty())
        assertTrue(decision.evidenceRequests.any { it.kind == ConvergenceEvidenceGapKind.CONTRADICTION })
    }

    @Test
    fun `demonstrated blocking missing capability routes tool workshop only`() {
        val fixture = fixture("capability", hypothesisCount = 1)
        val gap = CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId("capability.test.missing"),
                severity = GapSeverity.BLOCKING,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("result"),
            ),
            type = CapabilityGapType.CAPABILITY_MISSING,
            candidateProviderIds = emptyList(),
        )
        val base = decisionRequest(fixture)
        val request = base.copy(capabilityGaps = listOf(gap))

        val decision = ConvergenceDecisionEngine(
            ConvergenceDecisionPolicy(minTotalScore = 0.65)
        ).decide(request)

        assertEquals(ConvergenceDecisionState.CAPABILITY_REQUIRED, decision.state)
        assertEquals(ConvergenceEscalationTarget.TOOL_WORKSHOP, decision.escalation?.target)
        assertEquals(listOf("capability.test.missing"), decision.escalation?.capabilityIds)
        assertTrue(decision.escalation?.evidenceRequestIds.orEmpty().isEmpty())
    }

    @Test
    fun `decision identity changes when working set lineage changes`() {
        val fixture = fixture("lineage", hypothesisCount = 1)
        val base = decisionRequest(fixture)
        val engine = ConvergenceDecisionEngine(
            ConvergenceDecisionPolicy(minTotalScore = 0.65)
        )

        val first = engine.decide(base.copy(workingSetFingerprint = "working-set-a"))
        val second = engine.decide(base.copy(workingSetFingerprint = "working-set-b"))

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.sourceFingerprint, second.sourceFingerprint)
    }

    @Test
    fun `checkpoint codec round trips exact decision and snapshot lineage`() {
        val fixture = fixture("codec", hypothesisCount = 1)
        val request = decisionRequest(fixture)
        val policy = ConvergenceDecisionPolicy(minTotalScore = 0.65)
        val decision = ConvergenceDecisionEngine(policy).decide(request)
        val checkpoint = ConvergenceDecisionCheckpoint.create(request, decision, policy)

        val decoded = ConvergenceDecisionCheckpointCodec.decode(
            ConvergenceDecisionCheckpointCodec.encode(checkpoint)
        )

        assertEquals(checkpoint, decoded)
        assertEquals(checkpoint.contentFingerprint(), decoded.contentFingerprint())
        assertEquals(request.convergence.domainResults.single().snapshot.id, decoded.snapshots.single().snapshotId)
    }

    @Test
    fun `durable coordinator replays identical decision as duplicate without identity drift`() = runBlocking {
        val fixture = fixture("durable", hypothesisCount = 1)
        val request = decisionRequest(fixture)
        val policy = ConvergenceDecisionPolicy(minTotalScore = 0.65)
        val repository = InMemoryCheckpointRepository()
        val coordinator = DurableConvergenceDecisionCoordinator(repository, policy)

        val first = coordinator.decide(request)
        val second = coordinator.decide(request)

        assertEquals(first, second)
        assertEquals(2, repository.saveCalls)
        assertEquals(1, repository.checkpoints.size)
        assertEquals(listOf(first), coordinator.loadVerified())
    }

    private data class Fixture(
        val input: ConvergenceDomainInput,
        val hypotheses: List<FieldHypothesis>,
    )

    private fun fixture(
        name: String,
        hypothesisCount: Int,
        observedAt: Instant = now,
        conflictSeverity: Double? = null,
    ): Fixture {
        val domain = StableFieldIds.domain("decision.$name")
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = PhotonId("photon-$name"),
            sourceRevision = 1,
            kind = EvidenceKind.OBSERVATION,
            semanticKey = "evidence-$name",
            confidence = 1.0,
            reliability = EvidenceReliability(1.0, "verified-test-evidence"),
            authority = SourceAuthority.OFFICIAL,
            observedAt = observedAt,
            payload = EvidencePayload.text("evidence-$name"),
            explanation = "decision test evidence",
        )
        val nodes = (0 until hypothesisCount).map { index ->
            FieldNode.create(
                domainId = domain,
                kind = FieldNodeKind.CLAIM,
                semanticKey = "$name-node-$index",
                baseEnergy = 2.0,
                evidenceIds = setOf(evidence.id),
            )
        }
        val hypotheses = nodes.mapIndexed { index, node ->
            FieldHypothesis.create(
                domainId = domain,
                semanticKey = "$name-hypothesis-$index",
                scope = HypothesisScope.DOMAIN,
                nodeIds = setOf(node.id),
                evidenceLinks = listOf(
                    HypothesisEvidenceLink(evidence.id, EvidenceRelationType.SUPPORTS, 1.0)
                ),
                explanation = "decision hypothesis $index",
            )
        }
        val conflict = conflictSeverity?.let { severity ->
            require(nodes.size >= 2)
            FieldConflict(
                key = "decision-conflict-$name",
                nodeIds = nodes.take(2).mapTo(linkedSetOf()) { it.id },
                severity = severity,
                evidenceIds = setOf(evidence.id),
                explanation = "explicit decision conflict",
            )
        }
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(
                domainId = domain,
                nodes = nodes,
                conflicts = listOfNotNull(conflict),
            ),
            evidence = listOf(evidence),
            hypotheses = hypotheses,
            context = FieldContext(
                temporal = TemporalContext(now = now, queryTime = now),
                domain = DomainContext(domain),
            ),
        )
        return Fixture(
            input = ConvergenceDomainInput(request),
            hypotheses = hypotheses,
        )
    }

    private fun decisionRequest(fixture: Fixture): ConvergenceDecisionRequest {
        val source = CrossDomainConvergenceRequest(listOf(fixture.input))
        val convergence = ConvergenceCoordinator(DomainConvergenceRunner(fieldEngine::converge))
            .coordinate(source)
        return ConvergenceDecisionRequest(
            source = source,
            convergence = convergence,
            workingSetFingerprint = "working-set-test",
        )
    }

    private class InMemoryCheckpointRepository : ConvergenceDecisionCheckpointRepository {
        val checkpoints = linkedMapOf<ConvergenceDecisionCheckpointId, ConvergenceDecisionCheckpoint>()
        var saveCalls: Int = 0
            private set

        override suspend fun save(
            checkpoint: ConvergenceDecisionCheckpoint,
        ): ConvergenceDecisionCheckpointWriteResult {
            saveCalls += 1
            val previous = checkpoints.putIfAbsent(checkpoint.id, checkpoint)
            return if (previous == null) {
                ConvergenceDecisionCheckpointWriteResult.Stored(checkpoint)
            } else {
                require(previous == checkpoint)
                ConvergenceDecisionCheckpointWriteResult.Duplicate(previous)
            }
        }

        override suspend fun load(
            id: ConvergenceDecisionCheckpointId,
        ): ConvergenceDecisionCheckpoint? = checkpoints[id]

        override suspend fun loadReport(): ConvergenceDecisionCheckpointLoadReport =
            ConvergenceDecisionCheckpointLoadReport(
                checkpoints = checkpoints.values.toList(),
                unreadableEntries = emptyList(),
            )
    }
}
