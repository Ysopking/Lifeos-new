package app.lifeos.core.field.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldFieldModelGraphTest {
    @Test
    fun `vector keeps dimensions separate and rejects duplicate dimension`() {
        val support = value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.8, "support")
        val uncertainty = value(WorldSignalDimension.UNCERTAINTY, 0.3, "uncertainty")
        val vector = WorldFieldVector(listOf(uncertainty, support))

        assertEquals(0.8, vector[WorldSignalDimension.EVIDENCE_SUPPORT]?.value)
        assertEquals(0.3, vector[WorldSignalDimension.UNCERTAINTY]?.value)
        assertEquals(
            setOf(WorldSignalDimension.EVIDENCE_SUPPORT, WorldSignalDimension.UNCERTAINTY),
            vector.dimensions(),
        )

        assertFailsWith<IllegalArgumentException> {
            WorldFieldVector(listOf(support, support.copy(confidence = 0.5)))
        }
    }

    @Test
    fun `graph fingerprint is deterministic across node and edge input order`() {
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "support-to-priority",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.5,
            explanation = "test coefficient",
        )
        val source = node("domain", WorldNodeKind.DOMAIN_FIELD, WorldSignalDimension.EVIDENCE_SUPPORT, 0.9)
        val target = node("thought", WorldNodeKind.THOUGHT, WorldSignalDimension.COGNITIVE_PRIORITY, 0.2)
        val other = node("health", WorldNodeKind.HEALTH, WorldSignalDimension.HEALTH_STABILITY, 0.7)
        val edge = WorldFieldEdge.create(
            sourceNodeId = source.id,
            targetNodeId = target.id,
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            coefficientId = coefficient.id,
            strength = 0.75,
            explanation = "domain support raises thought priority",
        )

        val left = WorldFieldGraph(nodes = listOf(source, target, other), edges = listOf(edge))
        val right = WorldFieldGraph(nodes = listOf(other, target, source), edges = listOf(edge))

        assertEquals(left.fingerprint(), right.fingerprint())
        assertEquals(left.stableNodes().map { it.id }, right.stableNodes().map { it.id })
    }

    @Test
    fun `graph rejects edges that point outside graph`() {
        val source = node("source", WorldNodeKind.DOMAIN_FIELD, WorldSignalDimension.EVIDENCE_SUPPORT, 0.8)
        val missing = node("missing", WorldNodeKind.THOUGHT, WorldSignalDimension.COGNITIVE_PRIORITY, 0.1)
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "support",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.5,
            explanation = "test",
        )
        val edge = WorldFieldEdge.create(
            sourceNodeId = source.id,
            targetNodeId = missing.id,
            sourceDimension = coefficient.sourceDimension,
            targetDimension = coefficient.targetDimension,
            coefficientId = coefficient.id,
            strength = 1.0,
            explanation = "invalid external target",
        )

        assertFailsWith<IllegalArgumentException> {
            WorldFieldGraph(nodes = listOf(source), edges = listOf(edge))
        }
    }

    @Test
    fun `equation fingerprint changes when version or coefficient physics changes`() {
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "health-coupling",
            sourceDimension = WorldSignalDimension.CONFLICT_PRESSURE,
            targetDimension = WorldSignalDimension.HEALTH_STABILITY,
            multiplier = -0.4,
            explanation = "conflict reduces health stability",
        )
        val base = WorldEquationSpec("world-v1", listOf(coefficient))
        val physicsChanged = WorldEquationSpec("world-v1", listOf(coefficient.copy(multiplier = -0.7)))
        val versionChanged = WorldEquationSpec("world-v2", listOf(coefficient))

        assertNotEquals(base.fingerprint(), physicsChanged.fingerprint())
        assertNotEquals(base.fingerprint(), versionChanged.fingerprint())
        assertTrue(coefficient.id == coefficient.copy(multiplier = -0.7).id)
    }

    private fun node(
        key: String,
        kind: WorldNodeKind,
        dimension: WorldSignalDimension,
        magnitude: Double,
    ): WorldFieldNode = WorldFieldNode.create(
        target = WorldTargetRef(kind, key),
        intrinsic = WorldFieldVector(listOf(value(dimension, magnitude, key))),
    )

    private fun value(
        dimension: WorldSignalDimension,
        magnitude: Double,
        provenance: String,
    ) = WorldDimensionValue(
        dimension = dimension,
        value = magnitude,
        confidence = 1.0,
        provenanceFingerprints = setOf("prov-$provenance"),
    )
}
