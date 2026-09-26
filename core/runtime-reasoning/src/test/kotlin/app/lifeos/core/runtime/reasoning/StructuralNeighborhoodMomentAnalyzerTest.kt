package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtLifecycleStatus
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import app.lifeos.core.runtime.thought.ThoughtNode
import app.lifeos.core.runtime.thought.ThoughtNodeId
import app.lifeos.core.runtime.thought.ThoughtProvenance
import app.lifeos.core.runtime.thought.ThoughtRelation
import app.lifeos.core.runtime.thought.ThoughtRelationType
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus
import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class StructuralNeighborhoodMomentAnalyzerTest {
    private val at = Instant.parse("2026-09-26T12:00:00Z")

    @Test
    fun directionAndRelationTypeRemainPartOfStructuralIdentity() {
        val a = node("a")
        val b = node("b")
        val out = relation(a, b, ThoughtRelationType.REFERENCES)
        val inbound = relation(b, a, ThoughtRelationType.SUPPORTS)
        val snapshot = snapshot(listOf(a, b), listOf(out, inbound))

        val signature = StructuralNeighborhoodMomentAnalyzer().analyze(
            snapshot,
            a.photonId,
            StructuralAnalysisBudget(maxDepth = 1),
        )

        assertEquals(1, signature.layers.size)
        assertEquals(2, signature.layers.single().pathClassFingerprints.size)
        assertFalse(signature.budgetExhausted)
    }

    @Test
    fun missingTargetIsRetainedAsOpaqueBoundary() {
        val a = node("a")
        val missing = PhotonId("missing")
        val relation = ThoughtRelation.create(
            sourceNodeId = a.id,
            sourcePhotonId = a.photonId,
            targetPhotonId = missing,
            type = ThoughtRelationType.DERIVED_FROM,
            weight = 1.0,
            sourceRevision = 1,
            origin = "test",
        )

        val signature = StructuralNeighborhoodMomentAnalyzer().analyze(
            snapshot(listOf(a), listOf(relation)),
            a.photonId,
            StructuralAnalysisBudget(maxDepth = 1),
        )

        assertEquals(1, signature.layers.single().opaqueBoundaryCount)
    }

    private fun node(id: String): ThoughtNode {
        val photonId = PhotonId(id)
        return ThoughtNode(
            id = ThoughtNodeId.create(photonId, StableFieldIds.domain("test"), "key-$id"),
            provenance = ThoughtProvenance(
                sourcePhotonId = photonId,
                sourceRevision = 1,
                sourceFingerprint = StableFieldIds.fingerprint("source", id),
                source = "test",
                actor = "test",
                createdAt = at,
            ),
            fieldDomainId = StableFieldIds.domain("test"),
            semanticKey = "key-$id",
            summary = id,
            semanticMass = 1.0,
            energy = 1.0,
            confidence = 1.0,
            validity = TemporalValidity.UNBOUNDED,
            lifecycle = ThoughtLifecycleStatus.ACTIVE,
            verification = ThoughtVerificationStatus.OBSERVED,
            tags = emptySet(),
        )
    }

    private fun relation(
        source: ThoughtNode,
        target: ThoughtNode,
        type: ThoughtRelationType,
    ) = ThoughtRelation.create(
        sourceNodeId = source.id,
        sourcePhotonId = source.photonId,
        targetPhotonId = target.photonId,
        type = type,
        weight = 1.0,
        sourceRevision = 1,
        origin = "test",
    )

    private fun snapshot(
        nodes: List<ThoughtNode>,
        relations: List<ThoughtRelation>,
    ) = ThoughtMatrixSnapshot(
        revision = 1,
        nodes = nodes.sortedBy { it.id.value },
        relations = relations.sortedBy { it.id },
        conflicts = emptyList(),
        capturedAt = at,
    )
}
