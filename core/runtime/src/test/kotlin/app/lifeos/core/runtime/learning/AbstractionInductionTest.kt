package app.lifeos.core.runtime.learning

import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldNodeId
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionEntry
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionReason
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbstractionInductionTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun abstractionCandidateBindsThoughtPatternAndExactWorldSnapshotWithoutAuthority() {
        val workingSet = workingSet()
        val pattern = AbstractionPatternEvidence.fromWorkingSet(
            workingSet = workingSet,
            nodeIds = workingSet.nodes.mapTo(linkedSetOf()) { it.id.value },
        )
        val snapshot = worldSnapshot(
            dimensions = setOf(
                WorldSignalDimension.CAUSAL_SUPPORT,
                WorldSignalDimension.PREDICTIVE_FIT,
            )
        )

        val candidate = AbstractionCandidate.create(
            kind = AbstractionInductionKind.SCHEMA,
            semanticKey = "schema:strategy-outcome",
            summary = "strategy outcome schema",
            producerId = "abstraction-inducer-v1",
            workingSet = workingSet,
            pattern = pattern,
            worldSnapshot = snapshot,
            affectedTargets = setOf(
                WorldTargetRef(WorldNodeKind.WORLD_MODEL, "strategy-outcome-model")
            ),
            affectedDimensions = setOf(WorldSignalDimension.CAUSAL_SUPPORT),
            confidence = 0.81,
        )

        assertEquals(snapshot.id, candidate.worldSnapshotId)
        assertEquals(snapshot.contentFingerprint(), candidate.worldSnapshotFingerprint)
        assertEquals(workingSet.fingerprint, candidate.thoughtWorkingSetFingerprint)
        assertFalse(candidate.directSchemaMutationAllowed)
        assertFalse(candidate.directWorldStateMutationAllowed)
        assertFalse(candidate.activationAllowed)

        val validated = ValidatedAbstraction.create(
            candidate = candidate,
            validation = AbstractionValidationEvidence(
                candidateId = candidate.id,
                producerId = candidate.producerId,
                validatorId = "independent-abstraction-validator",
                passed = true,
                evidenceFingerprint = "validation-pass-v1",
            ),
        )
        val projected = AbstractionWorldProjectionCandidate.from(validated)

        assertEquals(candidate.id, projected.abstractionId)
        assertEquals(snapshot.id, projected.worldSnapshotId)
        assertFalse(projected.directWorldStateMutationAllowed)
        assertFalse(projected.activationAllowed)
    }

    @Test
    fun candidateRejectsDimensionAbsentFromBoundWorldSnapshot() {
        val workingSet = workingSet()
        val pattern = AbstractionPatternEvidence.fromWorkingSet(
            workingSet,
            workingSet.nodes.mapTo(linkedSetOf()) { it.id.value },
        )
        val snapshot = worldSnapshot(setOf(WorldSignalDimension.CAUSAL_SUPPORT))

        assertFailsWith<IllegalArgumentException> {
            AbstractionCandidate.create(
                kind = AbstractionInductionKind.RELATION,
                semanticKey = "relation:transfer",
                summary = "unsupported transfer relation",
                producerId = "abstraction-inducer-v1",
                workingSet = workingSet,
                pattern = pattern,
                worldSnapshot = snapshot,
                affectedTargets = setOf(
                    WorldTargetRef(WorldNodeKind.ABSTRACTION, "transfer-candidate")
                ),
                affectedDimensions = setOf(WorldSignalDimension.TRANSFER_RELEVANCE),
                confidence = 0.70,
            )
        }
    }

    @Test
    fun validationMustBeIndependentFromProducer() {
        val workingSet = workingSet()
        val pattern = AbstractionPatternEvidence.fromWorkingSet(
            workingSet,
            workingSet.nodes.mapTo(linkedSetOf()) { it.id.value },
        )
        val snapshot = worldSnapshot(setOf(WorldSignalDimension.CAUSAL_SUPPORT))
        val candidate = AbstractionCandidate.create(
            kind = AbstractionInductionKind.CONCEPT,
            semanticKey = "concept:causal-pattern",
            summary = "causal pattern",
            producerId = "same-identity",
            workingSet = workingSet,
            pattern = pattern,
            worldSnapshot = snapshot,
            affectedTargets = setOf(
                WorldTargetRef(WorldNodeKind.ABSTRACTION, "causal-pattern")
            ),
            affectedDimensions = setOf(WorldSignalDimension.CAUSAL_SUPPORT),
            confidence = 0.76,
        )

        assertFailsWith<IllegalArgumentException> {
            AbstractionValidationEvidence(
                candidateId = candidate.id,
                producerId = candidate.producerId,
                validatorId = candidate.producerId,
                passed = true,
                evidenceFingerprint = "invalid-self-validation",
            )
        }
    }

    @Test
    fun learningUpdaterEmitsReviewAndTypedInductionWorkWithoutClaimingStateChange() = runTest {
        val event = LearningEvent(
            sourceId = LearningSourceId("test.abstraction"),
            sequence = 1,
            eventId = "event-1",
            kind = LearningEventKind.COGNITIVE_OUTCOME,
            provenance = LearningProvenance.INFERENCE,
            occurredAt = now,
            attributes = mapOf(
                "abstractionPatternFingerprint" to "pattern-v1",
                "abstractionKind" to AbstractionInductionKind.SCHEMA.name,
            ),
        )

        val result = AbstractionLearningUpdater().update(event)

        assertFalse(result.changed)
        assertEquals(
            setOf(
                LearningDerivedWorkKind.ABSTRACTION_REVIEW,
                LearningDerivedWorkKind.SCHEMA_INDUCTION,
            ),
            result.workRequests.mapTo(linkedSetOf()) { it.kind },
        )
        assertTrue(result.evidence.contains("abstraction-pattern:pattern-v1"))
    }

    private fun workingSet(): ThoughtGraphWorkingSet {
        val first = node(
            sourceId = "evidence-1",
            kind = ThoughtGraphNodeKind.EVIDENCE,
            sourceKind = ThoughtGraphSourceKind.EVIDENCE,
            semanticKey = "signal-a",
        )
        val second = node(
            sourceId = "hypothesis-1",
            kind = ThoughtGraphNodeKind.HYPOTHESIS,
            sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
            semanticKey = "signal-b",
        )
        val edgeProvenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.SYSTEM,
            sourceId = "pattern-edge",
            sourceRevision = 1,
            sourceFingerprint = "pattern-edge-v1",
            origin = "abstraction-test",
            actor = "test",
            createdAt = now,
        )
        val edge = ThoughtGraphEdgeVersion.create(
            sourceNodeId = first.id,
            targetNodeId = second.id,
            kind = ThoughtGraphEdgeKind.SUPPORTS,
            semanticKey = "supports",
            confidence = 0.80,
            authority = 0.70,
            validity = TemporalValidity.UNBOUNDED,
            provenance = edgeProvenance,
            explanation = "test structural pattern",
        )
        val entries = listOf(
            ThoughtGraphAttentionEntry(
                nodeId = first.id,
                score = 2.0,
                reasons = listOf(ThoughtGraphAttentionReason.HIGH_CONFIDENCE),
            ),
            ThoughtGraphAttentionEntry(
                nodeId = second.id,
                score = 1.0,
                reasons = listOf(ThoughtGraphAttentionReason.HYPOTHESIS),
            ),
        )
        return ThoughtGraphWorkingSet(
            sourceSnapshotId = "thought-snapshot-v1",
            sourceRevision = 1,
            sourceHistoryFingerprint = "thought-history-v1",
            asOf = now,
            policy = ThoughtGraphAttentionPolicy(),
            entries = entries,
            nodes = listOf(first, second),
            edges = listOf(edge),
            conflicts = emptyList(),
        )
    }

    private fun node(
        sourceId: String,
        kind: ThoughtGraphNodeKind,
        sourceKind: ThoughtGraphSourceKind,
        semanticKey: String,
    ): ThoughtGraphNodeVersion {
        val provenance = ThoughtGraphProvenance(
            sourceKind = sourceKind,
            sourceId = sourceId,
            sourceRevision = 1,
            sourceFingerprint = "fingerprint-$sourceId",
            origin = "abstraction-test",
            actor = "test",
            createdAt = now,
        )
        return ThoughtGraphNodeVersion.create(
            kind = kind,
            semanticKey = semanticKey,
            summary = semanticKey,
            confidence = 0.80,
            authority = 0.70,
            validity = TemporalValidity.UNBOUNDED,
            provenance = provenance,
        )
    }

    private fun worldSnapshot(
        dimensions: Set<WorldSignalDimension>,
    ): WorldFormulaSnapshot {
        val vector = WorldFieldVector(
            dimensions.sortedBy { it.name }.map { dimension ->
                WorldDimensionValue(
                    dimension = dimension,
                    value = 0.70,
                    confidence = 0.80,
                    provenanceFingerprints = setOf("world-provenance-v1"),
                )
            }
        )
        val state = WorldFieldState(
            graphFingerprint = "graph-v1",
            equationFingerprint = "equation-v1",
            generation = 1,
            vectors = mapOf(
                WorldFieldNodeId("world-node-1") to vector,
            ),
        )
        return WorldFormulaSnapshot.create(
            runId = "run-v1",
            requestId = "request-v1",
            equationVersion = "lifeos-world-cognitive-v1",
            equationFingerprint = "equation-v1",
            graphFingerprint = "graph-v1",
            configFingerprint = "config-v1",
            status = WorldFormulaStatus.CONVERGED,
            finalState = state,
            iterations = emptyList(),
            conflicts = emptyList(),
            anomalies = emptyList(),
            inputSnapshotFingerprints = setOf("input-v1"),
        )
    }
}
