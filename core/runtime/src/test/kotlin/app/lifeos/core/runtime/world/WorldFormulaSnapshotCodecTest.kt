package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldEquationContribution
import app.lifeos.core.field.world.WorldFieldEdgeId
import app.lifeos.core.field.world.WorldFieldNodeId
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldSignalDimension
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WorldFormulaSnapshotCodecTest {
    @Test
    fun `snapshot round trips with exact content identity`() {
        val snapshot = snapshot()

        val encoded = WorldFormulaSnapshotCodec.encode(snapshot)
        val decoded = WorldFormulaSnapshotCodec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals(snapshot.id, decoded.id)
        assertEquals(snapshot.contentFingerprint(), decoded.contentFingerprint())
        assertContentEquals(encoded, WorldFormulaSnapshotCodec.encode(decoded))
    }

    @Test
    fun `trailing bytes are rejected`() {
        val encoded = WorldFormulaSnapshotCodec.encode(snapshot()) + byteArrayOf(1)

        assertFails { WorldFormulaSnapshotCodec.decode(encoded) }
    }

    @Test
    fun `truncated snapshot is rejected`() {
        val encoded = WorldFormulaSnapshotCodec.encode(snapshot())
        val truncated = encoded.copyOf(encoded.size - 5)

        assertFails { WorldFormulaSnapshotCodec.decode(truncated) }
    }

    @Test
    fun `oversized encoded input is rejected before parsing`() {
        val oversized = ByteArray(WorldFormulaSnapshotCodec.MAX_ENCODED_BYTES + 1)

        assertFails { WorldFormulaSnapshotCodec.decode(oversized) }
    }

    @Test
    fun `content addressed id rejects reconstructed snapshot with changed physics`() {
        val snapshot = snapshot()

        assertFails {
            snapshot.copy(
                finalState = snapshot.finalState.copy(
                    generation = snapshot.finalState.generation + 1,
                )
            )
        }
    }

    private fun snapshot(): WorldFormulaSnapshot {
        val sourceNode = WorldFieldNodeId("world-node:source")
        val targetNode = WorldFieldNodeId("world-node:target")
        val sourceVector = WorldFieldVector(
            listOf(
                WorldDimensionValue(
                    dimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                    value = 0.8,
                    confidence = 0.9,
                    provenanceFingerprints = setOf("source-field-snapshot"),
                )
            )
        )
        val targetVector = WorldFieldVector(
            listOf(
                WorldDimensionValue(
                    dimension = WorldSignalDimension.COGNITIVE_PRIORITY,
                    value = 0.4,
                    confidence = 0.7,
                    provenanceFingerprints = setOf("target-thought-snapshot", "contribution-proof"),
                )
            )
        )
        val state = WorldFieldState(
            graphFingerprint = "graph-fingerprint",
            equationFingerprint = "equation-fingerprint",
            generation = 1,
            vectors = mapOf(
                sourceNode to sourceVector,
                targetNode to targetVector,
            ).toSortedMap(compareBy<WorldFieldNodeId> { it.value }),
        )
        val contribution = WorldEquationContribution(
            edgeId = WorldFieldEdgeId("world-edge:support-priority"),
            sourceNodeId = sourceNode,
            targetNodeId = targetNode,
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            coefficientId = WorldCoefficientId("world-coefficient:support-priority"),
            signedDelta = 0.2,
            confidence = 0.72,
            provenanceFingerprint = "contribution-proof",
        )
        val iteration = WorldFormulaIteration(
            index = 1,
            beforeStateFingerprint = "before-state",
            afterStateFingerprint = state.fingerprint(),
            maxDelta = 0.2,
            stableRounds = 0,
            contributions = listOf(contribution),
        )
        val conflict = WorldFormulaConflict(
            key = "conflict-key",
            targetNodeId = targetNode,
            dimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            positiveEdgeIds = setOf("world-edge:positive"),
            negativeEdgeIds = setOf("world-edge:negative"),
            maxPositive = 0.3,
            maxNegativeMagnitude = 0.2,
        )
        val anomaly = WorldFormulaAnomaly(
            type = WorldFormulaAnomalyType.OPPOSING_INFLUENCES,
            key = "anomaly-key",
            detail = "opposing influences retained",
        )
        return WorldFormulaSnapshot.create(
            runId = "world-run:test",
            requestId = "world-request:test",
            equationVersion = "equation-v1",
            equationFingerprint = state.equationFingerprint,
            graphFingerprint = state.graphFingerprint,
            configFingerprint = "config-fingerprint",
            status = WorldFormulaStatus.UNRESOLVED,
            finalState = state,
            iterations = listOf(iteration),
            conflicts = listOf(conflict),
            anomalies = listOf(anomaly),
            inputSnapshotFingerprints = setOf("source-field-snapshot", "target-thought-snapshot"),
        )
    }
}
