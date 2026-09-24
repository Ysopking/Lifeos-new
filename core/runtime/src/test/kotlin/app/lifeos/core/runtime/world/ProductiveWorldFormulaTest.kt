package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.field.FieldWorldSignalProjection
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductiveWorldFormulaTest {
    private val observedAt = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun cognitiveProfilePreservesInformationalReplayVersionAndBindsFrozenCycle() {
        val profile = CognitiveWorldEquationProfile()
        val cycle = cycle(previous = null, id = "cycle-1")
        val request = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )

        assertEquals("lifeos-world-informational-v1", IntegratedWorldEquationProfile.VERSION)
        assertEquals("lifeos-world-cognitive-v1", profile.spec.version)
        assertEquals(profile.spec.version, request.request.equationVersion)
        assertEquals(cycle, request.cycle)
        assertEquals(TaskId("task-1"), request.request.sourceTaskId)
        assertEquals(PhotonId("photon-1"), request.request.photonId)
    }

    @Test
    fun perceptionBindingParticipatesInCycleFingerprintWithoutChangingLegacyFingerprintPath() {
        val legacy = cycle(previous = null, id = "cycle-legacy")
        val bound = legacy.copy(
            perceptionBinding = PersonalContextBootBinding(
                personalContextSnapshotId = "personal-context:" + "e".repeat(64),
                sensorRegistryFingerprint = "sensor-registry:" + "f".repeat(64),
                ownerObservationPolicyRevision = 11L,
            )
        )

        assertTrue(legacy.fingerprint() != bound.fingerprint())
        assertEquals(null, legacy.perceptionBinding)
        assertEquals(11L, bound.perceptionBinding?.ownerObservationPolicyRevision)
    }

    @Test
    fun persistedProductiveSnapshotCommitsThroughSeparateHeadCas() = runTest {
        val snapshots = InMemorySnapshots()
        val heads = InMemoryHeads()
        val profile = CognitiveWorldEquationProfile()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = snapshots,
        )
        val productive = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle(previous = null, id = "cycle-1"),
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val execution = coordinator.evaluate(productive.request)
        val candidate = ProductiveWorldCandidate.from(productive, execution)

        val committed = assertIs<ProductiveWorldCommitResult.Committed>(
            ProductiveWorldHeadCommitter(snapshots, heads).commit(
                candidate = candidate,
                expectedHead = null,
            )
        )

        assertEquals(1L, committed.currentHead.revision)
        assertEquals(candidate.snapshot.id, committed.currentHead.activeSnapshot.snapshotId)
        assertEquals(WorldFormulaSnapshotNamespace.PRODUCTIVE, committed.currentHead.activeSnapshot.namespace)
        assertEquals(CognitiveCycleId("cycle-1"), committed.currentHead.cycleId)
        assertNull(committed.currentHead.predecessorSnapshotId)
        assertEquals(candidate.snapshot, snapshots.load(candidate.snapshot.id))
        assertEquals(committed.currentHead, heads.load())
    }


    @Test
    fun nonProductiveExecutionCannotAdvanceProductiveWorld() = runTest {
        val snapshots = InMemorySnapshots()
        val profile = CognitiveWorldEquationProfile()
        val productive = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle(previous = null, id = "cycle-resource-scope"),
            sourceTaskId = TaskId("task-resource-scope"),
            photonId = PhotonId("photon-resource-scope"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val execution = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = snapshots,
        ).evaluate(productive.request).copy(
            scope = WorldFormulaExecutionScope.RESOURCE,
        )

        assertFailsWith<IllegalArgumentException> {
            ProductiveWorldCandidate.from(productive, execution)
        }
    }

    @Test
    fun nextProductiveCycleMustNameExactPreviousWorldSnapshot() = runTest {
        val snapshots = InMemorySnapshots()
        val heads = InMemoryHeads()
        val profile = CognitiveWorldEquationProfile()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = snapshots,
        )
        val firstRequest = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle(previous = null, id = "cycle-1"),
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val firstCandidate = ProductiveWorldCandidate.from(
            firstRequest,
            coordinator.evaluate(firstRequest.request),
        )
        val firstHead = assertIs<ProductiveWorldCommitResult.Committed>(
            ProductiveWorldHeadCommitter(snapshots, heads).commit(firstCandidate, null)
        ).currentHead

        val staleRequest = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt.plusSeconds(1),
            cycle = cycle(previous = "wrong-snapshot", id = "cycle-2"),
            sourceTaskId = TaskId("task-2"),
            photonId = PhotonId("photon-2"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val staleCandidate = ProductiveWorldCandidate.from(
            staleRequest,
            coordinator.evaluate(staleRequest.request),
        )

        val blocked = assertIs<ProductiveWorldCommitResult.Blocked>(
            ProductiveWorldHeadCommitter(snapshots, heads).commit(
                candidate = staleCandidate,
                expectedHead = firstHead,
            )
        )
        assertEquals("productive-world-predecessor-mismatch", blocked.reason)
        assertEquals(firstHead, heads.load())
    }

    @Test
    fun unpublishedSnapshotCannotAdvanceProductiveHead() = runTest {
        val snapshots = InMemorySnapshots()
        val heads = InMemoryHeads()
        val profile = CognitiveWorldEquationProfile()
        val productive = profile.request(
            projection = projection(),
            links = emptyList(),
            observedAt = observedAt,
            cycle = cycle(previous = null, id = "cycle-1"),
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            config = WorldFormulaConfig(requiredStableRounds = 1),
        )
        val temporary = InMemorySnapshots()
        val execution = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = temporary,
        ).evaluate(productive.request)
        val candidate = ProductiveWorldCandidate.from(productive, execution)

        val blocked = assertIs<ProductiveWorldCommitResult.Blocked>(
            ProductiveWorldHeadCommitter(snapshots, heads).commit(candidate, null)
        )

        assertEquals("productive-world-snapshot-not-persisted", blocked.reason)
        assertNull(heads.load())
        assertTrue(snapshots.all.isEmpty())
    }

    private fun cycle(
        previous: String?,
        id: String,
    ) = WorldFormulaCycleContext(
        cycleId = CognitiveCycleId(id),
        previousWorldSnapshotId = previous,
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

    private class InMemorySnapshots : WorldFormulaSnapshotRepository {
        val all = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            all[snapshot.id]?.let { existing -> require(existing == snapshot) }
            all[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = all[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = all.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(all.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            all.remove(id)
        }
    }

    private class InMemoryHeads : ProductiveWorldHeadRepository {
        private var head: ProductiveWorldHead? = null

        override suspend fun load(): ProductiveWorldHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: ProductiveWorldHead,
        ): Boolean {
            if (head?.revision != expectedRevision) return false
            head = next
            return true
        }
    }
}
