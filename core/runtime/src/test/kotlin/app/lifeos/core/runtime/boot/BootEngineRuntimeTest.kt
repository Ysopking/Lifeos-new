package app.lifeos.core.runtime.boot

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.field.FieldWorldSignalProjection
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.ProductiveWorldCommitResult
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.ProductiveWorldHeadCommitter
import app.lifeos.core.runtime.world.ProductiveWorldHeadRepository
import app.lifeos.core.runtime.world.WorldFormulaConfig
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BootEngineRuntimeTest {
    private val observedAt = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun bootEngineOwnsOneFrozenWorldPhysicsContextPerCycle() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()

        val cycle = runtime.startCycle(frozenInputs())
        val request = fixture.profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle.context,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val evaluation = assertIs<BootEngineWorldEvaluation.Ready>(
            runtime.evaluate(cycle.cycleId, request)
        )
        val committed = assertIs<BootEngineCommitResult.Committed>(
            runtime.commit(evaluation)
        )

        assertEquals(BootEngineCycleState.COMMITTED, committed.cycle.state)
        assertEquals(CognitiveWorldEquationProfile.VERSION, committed.cycle.context.equationVersion)
        assertEquals(request.cycle.fingerprint(), committed.worldHead.cycleContextFingerprint)
        assertEquals(request.cycle.cycleId, committed.worldHead.cycleId)
    }

    @Test
    fun perceptionBindingIsFrozenIntoProductiveCycleAndWorldHead() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()
        val binding = PersonalContextBootBinding(
            personalContextSnapshotId = "personal-context:" + "a".repeat(64),
            sensorRegistryFingerprint = "sensor-registry:" + "b".repeat(64),
            ownerObservationPolicyRevision = 7L,
        )
        val cycle = runtime.startCycle(
            frozenInputs().copy(perceptionBinding = binding)
        )

        assertEquals(binding, cycle.context.perceptionBinding)

        val request = fixture.profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle.context,
            sourceTaskId = TaskId("task-perception"),
            photonId = PhotonId("photon-perception"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val evaluation = assertIs<BootEngineWorldEvaluation.Ready>(
            runtime.evaluate(cycle.cycleId, request)
        )
        val committed = assertIs<BootEngineCommitResult.Committed>(
            runtime.commit(evaluation)
        )

        assertEquals(
            cycle.context.fingerprint(),
            committed.worldHead.cycleContextFingerprint,
        )
        assertEquals(binding, committed.cycle.context.perceptionBinding)
    }

    @Test
    fun changedPerceptionBindingIsRejectedInsideActiveCycle() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()
        val binding = PersonalContextBootBinding(
            personalContextSnapshotId = "personal-context:" + "c".repeat(64),
            sensorRegistryFingerprint = "sensor-registry:" + "d".repeat(64),
            ownerObservationPolicyRevision = 3L,
        )
        val cycle = runtime.startCycle(
            frozenInputs().copy(perceptionBinding = binding)
        )
        val changed = cycle.context.copy(
            perceptionBinding = binding.copy(
                ownerObservationPolicyRevision = 4L,
            )
        )
        val request = fixture.profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = changed,
            sourceTaskId = TaskId("task-perception-change"),
            photonId = PhotonId("photon-perception-change"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.evaluate(cycle.cycleId, request)
        }
        assertEquals(
            BootEngineCycleState.PREPARED,
            fixture.cycles.load(cycle.cycleId)?.state,
        )
        assertNull(fixture.heads.load())
    }

    @Test
    fun changedFrozenContextIsRejectedInsideActiveCycle() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()
        val cycle = runtime.startCycle(frozenInputs())
        val changed = cycle.context.copy(strategySnapshotId = "strategy-v2")
        val request = fixture.profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = changed,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )

        var rejected = false
        try {
            runtime.evaluate(cycle.cycleId, request)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        kotlin.test.assertTrue(rejected)
        assertEquals(BootEngineCycleState.PREPARED, fixture.cycles.load(cycle.cycleId)?.state)
        assertNull(fixture.heads.load())
    }

    @Test
    fun recoveryFinalizesCycleAfterWorldHeadCommittedBeforeCycleLedger() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()
        val cycle = runtime.startCycle(frozenInputs())
        val request = fixture.profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle.context,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val evaluation = assertIs<BootEngineWorldEvaluation.Ready>(
            runtime.evaluate(cycle.cycleId, request)
        )

        val worldCommit = assertIs<ProductiveWorldCommitResult.Committed>(
            ProductiveWorldHeadCommitter(
                snapshots = fixture.snapshots,
                heads = fixture.heads,
            ).commit(
                candidate = evaluation.candidate,
                expectedHead = null,
            )
        )
        assertEquals(BootEngineCycleState.WORLD_EVALUATED, fixture.cycles.load(cycle.cycleId)?.state)

        val recovered = assertIs<BootEngineRecoveryResult.RecoveredCommitted>(
            runtime.recover()
        )

        assertEquals(worldCommit.currentHead.revision, recovered.cycle.productiveHeadRevision)
        assertEquals(BootEngineCycleState.COMMITTED, recovered.cycle.state)
        assertNull(fixture.cycles.loadActive())
    }

    private fun fixture(): Fixture {
        val snapshots = InMemorySnapshots()
        val heads = InMemoryHeads()
        val cycles = InMemoryCycles()
        val profile = CognitiveWorldEquationProfile()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = snapshots,
        )
        return Fixture(
            snapshots = snapshots,
            heads = heads,
            cycles = cycles,
            profile = profile,
            coordinator = coordinator,
        )
    }

    private fun frozenInputs() = BootEngineFrozenInputs(
        representationSnapshotId = "representation-v1",
        strategySnapshotId = "strategy-v1",
        equationVersion = CognitiveWorldEquationProfile.VERSION,
        resourceSnapshotId = "resource-v1",
    )

    private fun projection() = FieldWorldSignalProjection(
        inputs = listOf(
            WorldFormulaInputSnapshot(
                target = WorldTargetRef(WorldNodeKind.PHOTON, "photon-world"),
                vector = WorldFieldVector.EMPTY,
                sourceSnapshotFingerprint = "source-snapshot-v1",
            )
        ),
        fieldSnapshotFingerprint = "field-v1",
        workingSetFingerprint = null,
        calibrationFingerprint = "calibration-v1",
        configFingerprint = "projection-config-v1",
    )

    private data class Fixture(
        val snapshots: InMemorySnapshots,
        val heads: InMemoryHeads,
        val cycles: InMemoryCycles,
        val profile: CognitiveWorldEquationProfile,
        val coordinator: WorldFormulaCoordinator,
    ) {
        private var nextCycle = 0

        fun runtime(): BootEngineRuntime = BootEngineRuntime(
            cycles = cycles,
            worldHeads = heads,
            worldCoordinator = coordinator,
            worldCommitter = ProductiveWorldHeadCommitter(snapshots, heads),
            newCycleId = {
                nextCycle += 1
                CognitiveCycleId("cycle-$nextCycle")
            },
        )
    }

    private class InMemorySnapshots : WorldFormulaSnapshotRepository {
        private val values = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            values[snapshot.id]?.let { existing -> require(existing == snapshot) }
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

    private class InMemoryHeads : ProductiveWorldHeadRepository {
        private var value: ProductiveWorldHead? = null

        override suspend fun load(): ProductiveWorldHead? = value

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: ProductiveWorldHead,
        ): Boolean {
            if (value?.revision != expectedRevision) return false
            value = next
            return true
        }
    }

    private class InMemoryCycles : BootEngineCycleRepository {
        private val values = linkedMapOf<CognitiveCycleId, BootEngineCycle>()

        override suspend fun create(cycle: BootEngineCycle): Boolean {
            if (cycle.cycleId in values) return false
            if (loadActive() != null) return false
            values[cycle.cycleId] = cycle
            return true
        }

        override suspend fun load(cycleId: CognitiveCycleId): BootEngineCycle? =
            values[cycleId]

        override suspend fun loadActive(): BootEngineCycle? =
            values.values.firstOrNull { !it.terminal }

        override suspend fun compareAndSet(
            expectedFingerprint: String,
            next: BootEngineCycle,
        ): Boolean {
            val current = values[next.cycleId] ?: return false
            if (current.fingerprint != expectedFingerprint) return false
            values[next.cycleId] = next
            return true
        }
    }
}
