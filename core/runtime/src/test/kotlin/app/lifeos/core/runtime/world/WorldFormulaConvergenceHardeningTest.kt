package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.field.world.WorldTransferCoefficient
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldFormulaConvergenceHardeningTest {
    @Test
    fun `confidence-only state change requires another stable round`() = runBlocking {
        val source = WorldFormulaInputSnapshot(
            target = WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, "confidence-source"),
            vector = WorldFieldVector(
                listOf(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                        value = 0.0,
                        confidence = 1.0,
                        provenanceFingerprints = setOf("source-snapshot"),
                    )
                )
            ),
            sourceSnapshotFingerprint = "source-snapshot",
        )
        val target = WorldFormulaInputSnapshot(
            target = WorldTargetRef(WorldNodeKind.THOUGHT, "confidence-target"),
            vector = WorldFieldVector(
                listOf(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.COGNITIVE_PRIORITY,
                        value = 0.5,
                        confidence = 0.1,
                        provenanceFingerprints = setOf("target-snapshot"),
                    )
                )
            ),
            sourceSnapshotFingerprint = "target-snapshot",
        )
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "confidence-only-transfer",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.0,
            confidenceMultiplier = 1.0,
            explanation = "zero value transfer can still carry confidence",
        )
        val request = WorldFormulaRequest(
            inputs = listOf(source, target),
            interactions = listOf(
                WorldFormulaInteraction(
                    source = source.target,
                    target = target.target,
                    sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                    targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
                    coefficientId = coefficient.id,
                    strength = 1.0,
                    explanation = "confidence propagation test",
                )
            ),
            equationVersion = "confidence-v1",
            observedAt = Instant.parse("2026-09-10T13:00:00Z"),
            config = WorldFormulaConfig(
                maxIterations = 3,
                requiredStableRounds = 1,
                epsilon = 0.0,
            ),
        )
        val repository = RecordingRepository()
        val result = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(
                listOf(WorldEquationSpec("confidence-v1", listOf(coefficient)))
            ),
            snapshots = repository,
        ).evaluate(request)

        assertEquals(WorldFormulaStatus.CONVERGED, result.status)
        assertEquals(2, result.snapshot?.iterations?.size)
        assertEquals(0.9, result.snapshot?.iterations?.first()?.maxDelta)
        assertEquals(0.0, result.snapshot?.iterations?.last()?.maxDelta)
    }

    private class RecordingRepository : WorldFormulaSnapshotRepository {
        private val values = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            values[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = values[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = values.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(values.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            values.remove(id)
        }
    }
}
