package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionProjector
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSnapshot
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldWorldSignalProjectorTest {
    private val time = Instant.parse("2026-09-11T08:00:00Z")

    @Test
    fun `field result plus attention projects typed bounded provenance preserving world inputs`() {
        val photon = photon()
        val request = DefaultPhotonFieldRequestFactory().create(photon)
        val result = FieldConvergenceEngine().converge(request)
        val workingSet = workingSetFor(result.hypotheses.single().id.value)
        val projector = FieldWorldSignalProjector()

        val first = projector.project(photon, request, result, workingSet)
        val second = projector.project(photon, request, result, workingSet)

        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
        val kinds = first.inputs.map { it.target.kind }.toSet()
        assertTrue(WorldNodeKind.PHOTON in kinds)
        assertTrue(WorldNodeKind.EVIDENCE in kinds)
        assertTrue(WorldNodeKind.HYPOTHESIS in kinds)
        assertTrue(WorldNodeKind.DOMAIN_FIELD in kinds)
        assertTrue(WorldNodeKind.GOAL in kinds)
        first.inputs.flatMap { it.vector.values }.forEach { value ->
            assertTrue(value.value in 0.0..1.0)
            assertTrue(value.confidence in 0.0..1.0)
            assertTrue(value.provenanceFingerprints.isNotEmpty())
        }
        val hypothesis = first.inputs.single { it.target.kind == WorldNodeKind.HYPOTHESIS }
        assertTrue(assertNotNull(hypothesis.vector[WorldSignalDimension.GOAL_RELEVANCE]).value > 0.0)
        assertNotNull(hypothesis.vector[WorldSignalDimension.RELIABILITY])
        assertNotNull(hypothesis.vector[WorldSignalDimension.AUTHORITY])
        assertNotNull(hypothesis.vector[WorldSignalDimension.CONFLICT_PRESSURE])
    }

    private fun workingSetFor(hypothesisId: String) = run {
        val goalProvenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.GOAL,
            sourceId = "goal-1",
            sourceRevision = 1,
            sourceFingerprint = "goal-source-fingerprint",
            origin = "test",
            actor = "test",
            createdAt = time,
        )
        val hypothesisProvenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
            sourceId = hypothesisId,
            sourceRevision = 1,
            sourceFingerprint = "hypothesis-source-fingerprint",
            origin = "test",
            actor = "test",
            createdAt = time,
        )
        val goal = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.GOAL,
            semanticKey = "goal",
            summary = "goal",
            confidence = 0.9,
            authority = 0.8,
            validity = TemporalValidity.UNBOUNDED,
            provenance = goalProvenance,
        )
        val hypothesis = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.HYPOTHESIS,
            semanticKey = "hypothesis",
            summary = "hypothesis",
            confidence = 0.8,
            authority = 0.7,
            validity = TemporalValidity.UNBOUNDED,
            provenance = hypothesisProvenance,
            attributes = mapOf("state" to "UNRESOLVED"),
        )
        val edge = ThoughtGraphEdgeVersion.create(
            sourceNodeId = hypothesis.id,
            targetNodeId = goal.id,
            kind = ThoughtGraphEdgeKind.TARGETS_GOAL,
            semanticKey = "targets-goal",
            confidence = 0.8,
            authority = 0.7,
            validity = TemporalValidity.UNBOUNDED,
            provenance = hypothesisProvenance,
            explanation = "test goal relevance",
        )
        val snapshot = ThoughtGraphSnapshot(
            revision = 0,
            activeNodes = listOf(goal, hypothesis).sortedBy { it.id.value },
            activeEdges = listOf(edge),
            conflicts = emptyList(),
            nodeHistoryCount = 2,
            edgeHistoryCount = 1,
            appliedDeltaIds = emptyList(),
            capturedAt = time,
            historyFingerprint = "history-fingerprint",
        )
        ThoughtGraphAttentionProjector().project(snapshot, asOf = time)
    }

    private fun photon(): Photon = Photon(
        id = PhotonId("v4-world-projection-photon"),
        revision = 1,
        content = "project this field result into world signals",
        semanticMass = 1.2,
        energy = 0.8,
        confidence = 0.9,
        provenance = Provenance(
            source = "v4-test",
            actor = "test",
            createdAt = time,
        ),
    )
}
