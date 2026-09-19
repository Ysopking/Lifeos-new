package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.field.world.WorldTransferCoefficient
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.cognition.CognitiveTrigger
import app.lifeos.core.runtime.cognition.CognitiveTriggerSink
import app.lifeos.core.runtime.cognition.CognitiveTriggerType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldFormulaCoordinatorTest {
    private val observedAt = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `same inputs equation and config replay to same run and snapshot`() = runBlocking {
        val fixture = fixture()
        val repository = InMemoryWorldRepository()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(fixture.spec)),
            snapshots = repository,
        )

        val first = coordinator.evaluate(fixture.request)
        val second = coordinator.evaluate(fixture.request)

        assertEquals(WorldFormulaExecutionState.COMPLETED, first.state)
        assertEquals(WorldFormulaStatus.CONVERGED, first.status)
        assertTrue(first.persisted)
        assertEquals(first.snapshot?.runId, second.snapshot?.runId)
        assertEquals(first.snapshot?.id, second.snapshot?.id)
        assertEquals(1, repository.all.size)
        assertEquals(2, first.snapshot?.iterations?.size)
    }

    @Test
    fun `missing equation is invalid and never persisted`() = runBlocking {
        val fixture = fixture()
        val repository = InMemoryWorldRepository()
        val result = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(),
            snapshots = repository,
        ).evaluate(fixture.request)

        assertEquals(WorldFormulaExecutionState.INVALID, result.state)
        assertEquals(WorldFormulaStatus.INVALID_EQUATION, result.status)
        assertFalse(result.persisted)
        assertNull(result.snapshot)
        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `persistence failure prevents completed state and cognition trigger`() = runBlocking {
        val fixture = fixture(maxIterations = 1)
        val triggers = RecordingTriggerSink()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(fixture.spec)),
            snapshots = object : WorldFormulaSnapshotRepository {
                override suspend fun save(snapshot: WorldFormulaSnapshot) { error("disk unavailable") }
                override suspend fun load(id: String): WorldFormulaSnapshot? = null
                override suspend fun loadLatest(): WorldFormulaSnapshot? = null
                override suspend fun loadReport() = WorldFormulaSnapshotLoadReport(emptyList(), emptyList())
                override suspend fun delete(id: String) = Unit
            },
            triggerSink = triggers,
        )

        val result = coordinator.evaluate(fixture.request)

        assertEquals(WorldFormulaExecutionState.PERSISTENCE_FAILED, result.state)
        assertEquals(WorldFormulaStatus.MAX_ITERATIONS, result.status)
        assertFalse(result.persisted)
        assertNotNull(result.snapshot)
        assertTrue(triggers.values.isEmpty())
    }

    @Test
    fun `max iteration snapshot is persisted then emits deterministic reevaluation trigger`() = runBlocking {
        val fixture = fixture(maxIterations = 1)
        val repository = InMemoryWorldRepository()
        val triggers = RecordingTriggerSink()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(fixture.spec)),
            snapshots = repository,
            triggerSink = triggers,
        )

        val result = coordinator.evaluate(fixture.request)

        assertEquals(WorldFormulaExecutionState.COMPLETED, result.state)
        assertEquals(WorldFormulaStatus.MAX_ITERATIONS, result.status)
        assertTrue(result.persisted)
        assertEquals(1, triggers.values.size)
        val trigger = triggers.values.single()
        assertEquals(CognitiveTriggerType.REEVALUATE, trigger.type)
        assertEquals(fixture.request.sourceTaskId, trigger.sourceTaskId)
        assertEquals(fixture.request.photonId, trigger.photonId)
        assertEquals(observedAt, trigger.createdAt)
        assertTrue(trigger.id.startsWith("world-trigger:"))
    }

    @Test
    fun `trigger sink failure cannot invalidate persisted world snapshot`() = runBlocking {
        val fixture = fixture(maxIterations = 1)
        val repository = InMemoryWorldRepository()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(fixture.spec)),
            snapshots = repository,
            triggerSink = object : CognitiveTriggerSink {
                override suspend fun emit(trigger: CognitiveTrigger): Boolean = error("journal unavailable")
                override suspend fun snapshot(): List<CognitiveTrigger> = emptyList()
            },
        )

        val result = coordinator.evaluate(fixture.request)

        assertEquals(WorldFormulaExecutionState.COMPLETED, result.state)
        assertTrue(result.persisted)
        assertEquals(result.snapshot, repository.load(requireNotNull(result.snapshot).id))
    }

    @Test
    fun `opposing material influences preserve unresolved state`() = runBlocking {
        val positiveSource = input(
            WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, "positive"),
            WorldSignalDimension.EVIDENCE_SUPPORT,
            1.0,
            "positive-snapshot",
        )
        val negativeSource = input(
            WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, "negative"),
            WorldSignalDimension.CONFLICT_PRESSURE,
            1.0,
            "negative-snapshot",
        )
        val target = input(
            WorldTargetRef(WorldNodeKind.CAPABILITY, "candidate"),
            WorldSignalDimension.CAPABILITY_READINESS,
            0.5,
            "target-snapshot",
        )
        val raise = WorldTransferCoefficient.create(
            "raise-readiness",
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.CAPABILITY_READINESS,
            0.4,
            explanation = "support raises readiness",
        )
        val lower = WorldTransferCoefficient.create(
            "lower-readiness",
            WorldSignalDimension.CONFLICT_PRESSURE,
            WorldSignalDimension.CAPABILITY_READINESS,
            -0.4,
            explanation = "conflict lowers readiness",
        )
        val request = WorldFormulaRequest(
            inputs = listOf(target, negativeSource, positiveSource),
            interactions = listOf(
                WorldFormulaInteraction(
                    positiveSource.target,
                    target.target,
                    WorldSignalDimension.EVIDENCE_SUPPORT,
                    WorldSignalDimension.CAPABILITY_READINESS,
                    raise.id,
                    1.0,
                    "raise",
                ),
                WorldFormulaInteraction(
                    negativeSource.target,
                    target.target,
                    WorldSignalDimension.CONFLICT_PRESSURE,
                    WorldSignalDimension.CAPABILITY_READINESS,
                    lower.id,
                    1.0,
                    "lower",
                ),
            ),
            equationVersion = "opposition-v1",
            observedAt = observedAt,
            config = WorldFormulaConfig(requiredStableRounds = 1, opposingContributionThreshold = 0.1),
            sourceTaskId = TaskId("task-opposition"),
        )
        val repository = InMemoryWorldRepository()
        val result = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(WorldEquationSpec("opposition-v1", listOf(lower, raise)))),
            snapshots = repository,
        ).evaluate(request)

        assertEquals(WorldFormulaStatus.UNRESOLVED, result.status)
        assertEquals(1, result.snapshot?.conflicts?.size)
        assertTrue(result.snapshot?.anomalies?.any { it.type == WorldFormulaAnomalyType.OPPOSING_INFLUENCES } == true)
    }

    private data class Fixture(
        val spec: WorldEquationSpec,
        val request: WorldFormulaRequest,
    )

    private fun fixture(maxIterations: Int = 4): Fixture {
        val source = input(
            WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, "field-a"),
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.8,
            "field-snapshot-a",
        )
        val target = input(
            WorldTargetRef(WorldNodeKind.THOUGHT, "thought-a"),
            WorldSignalDimension.COGNITIVE_PRIORITY,
            0.1,
            "thought-snapshot-a",
        )
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "support-to-priority",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.5,
            explanation = "support raises cognitive priority",
        )
        val spec = WorldEquationSpec("world-test-v1", listOf(coefficient))
        val request = WorldFormulaRequest(
            inputs = listOf(target, source),
            interactions = listOf(
                WorldFormulaInteraction(
                    source = source.target,
                    target = target.target,
                    sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                    targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
                    coefficientId = coefficient.id,
                    strength = 1.0,
                    explanation = "test interaction",
                )
            ),
            equationVersion = spec.version,
            observedAt = observedAt,
            config = WorldFormulaConfig(
                maxIterations = maxIterations,
                requiredStableRounds = 1,
                epsilon = 0.0,
            ),
            sourceTaskId = TaskId("task-world"),
            photonId = PhotonId("photon-world"),
        )
        return Fixture(spec, request)
    }

    private fun input(
        target: WorldTargetRef,
        dimension: WorldSignalDimension,
        value: Double,
        fingerprint: String,
    ) = WorldFormulaInputSnapshot(
        target = target,
        vector = WorldFieldVector(
            listOf(
                WorldDimensionValue(
                    dimension = dimension,
                    value = value,
                    confidence = 1.0,
                    provenanceFingerprints = setOf(fingerprint),
                )
            )
        ),
        sourceSnapshotFingerprint = fingerprint,
    )

    private class InMemoryWorldRepository : WorldFormulaSnapshotRepository {
        val all = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            all[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = all[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = all.values.lastOrNull()

        override suspend fun loadReport() = WorldFormulaSnapshotLoadReport(all.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            all.remove(id)
        }
    }

    private class RecordingTriggerSink : CognitiveTriggerSink {
        val values = mutableListOf<CognitiveTrigger>()

        override suspend fun emit(trigger: CognitiveTrigger): Boolean {
            if (values.any { it.id == trigger.id }) return false
            values += trigger
            return true
        }

        override suspend fun snapshot(): List<CognitiveTrigger> = values.toList()
    }
}
