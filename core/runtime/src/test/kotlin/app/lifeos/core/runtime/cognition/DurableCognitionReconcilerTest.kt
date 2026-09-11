package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskLoadReport
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableCognitionReconcilerTest {
    @Test
    fun persistedPhotonWithoutTaskIsDurabilizedExactlyOnceAcrossReconcileReplay() = runTest {
        val photon = photon("missing-task", revision = 1)
        val photons = TestPhotonRepository(listOf(photon))
        val tasks = SnapshotTaskRepository()
        val reconciler = reconciler(photons, tasks)

        val first = reconciler.reconcile()
        val second = reconciler.reconcile()

        assertEquals(1, first.submitted)
        assertEquals(0, first.alreadyCovered)
        assertEquals(0, second.submitted)
        assertEquals(1, second.alreadyCovered)
        assertEquals(1, tasks.snapshot().size)
        assertNotNull(
            tasks.findByIdempotencyKey(
                "cognition:photon:missing-task:revision:1:photon:missing-task:revision:1:pipeline:1"
            )
        )
    }

    @Test
    fun legacyTaskForExactPhotonRevisionCountsAsDurableCoverage() = runTest {
        val photon = photon("legacy-covered", revision = 3)
        val photons = TestPhotonRepository(listOf(photon))
        val tasks = SnapshotTaskRepository()
        tasks.create(
            LifeTask(
                type = TaskType.PROCESS_PHOTON,
                inputPhotonIds = setOf(photon.id),
                inputPhotonRevisions = mapOf(photon.id to photon.revision),
                idempotencyKey = "legacy-random-delta-key",
            )
        )

        val result = reconciler(photons, tasks).reconcile()

        assertEquals(1, result.alreadyCovered)
        assertEquals(0, result.submitted)
        assertEquals(1, tasks.snapshot().size)
    }

    @Test
    fun newerPhotonRevisionGetsDistinctDurableWork() = runTest {
        val revisionOne = photon("revisioned", revision = 1)
        val photons = TestPhotonRepository(listOf(revisionOne))
        val tasks = SnapshotTaskRepository()
        val reconciler = reconciler(photons, tasks)

        assertEquals(1, reconciler.reconcile().submitted)
        photons.save(revisionOne.copy(revision = 2, content = "revision-2"))
        val second = reconciler.reconcile()

        assertEquals(1, second.submitted)
        assertEquals(2, tasks.snapshot().size)
        assertTrue(tasks.snapshot().any { it.inputPhotonRevisions[revisionOne.id] == 1L })
        assertTrue(tasks.snapshot().any { it.inputPhotonRevisions[revisionOne.id] == 2L })
    }

    @Test
    fun terminalFailureForExactRevisionRemainsCovered() = runTest {
        val photon = photon("failed-covered", revision = 4)
        val failed = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.FAILED,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "failed-existing",
        )
        val tasks = SnapshotTaskRepository(snapshotExtras = listOf(failed))

        val result = reconciler(TestPhotonRepository(listOf(photon)), tasks).reconcile()

        assertEquals(1, result.alreadyCovered)
        assertEquals(0, result.submitted)
    }

    @Test
    fun stableDeltaIdentityDependsOnlyOnPhotonAndRevision() {
        val photonId = PhotonId("stable")
        assertEquals(
            "photon:stable:revision:7",
            CognitiveDeltaIdentity.photonRevision(photonId, 7),
        )
    }

    private fun reconciler(
        photons: PhotonRepository,
        tasks: SnapshotTaskRepository,
    ): DurableCognitionReconciler {
        val taskEngine = DurableTaskEngine(
            tasks = tasks,
            schedulerSignal = TaskSchedulerSignal { },
            now = { Instant.parse("2026-09-11T10:00:00Z") },
        )
        val cognition = ContinuousCognitionEngine(
            journal = InMemoryCognitiveEventJournal(),
            scheduler = CognitiveScheduler(),
            durableDispatcher = DurableCognitionDispatcher(taskEngine),
        )
        return DurableCognitionReconciler(
            photons = photons,
            tasks = tasks,
            cognition = cognition,
        )
    }

    private fun photon(id: String, revision: Long): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = "revision-$revision",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse("2026-09-11T09:00:00Z"),
        ),
    )

    private class TestPhotonRepository(initial: List<Photon>) : PhotonRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = loadAll(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }

    private class SnapshotTaskRepository(
        private val delegate: InMemoryTaskRepository = InMemoryTaskRepository(),
        private val snapshotExtras: List<LifeTask> = emptyList(),
    ) : TaskSnapshotRepository, TaskRepository by delegate {
        private val knownIds = linkedSetOf<app.lifeos.core.model.task.TaskId>()

        override suspend fun create(task: LifeTask): CreateTaskResult {
            val result = delegate.create(task)
            val stored = when (result) {
                is CreateTaskResult.Created -> result.task
                is CreateTaskResult.Existing -> result.task
            }
            knownIds += stored.id
            return result
        }

        override suspend fun loadReport(): TaskLoadReport = TaskLoadReport(
            tasks = knownIds.mapNotNull { delegate.get(it) } + snapshotExtras,
            unreadableEntries = emptyList(),
        )

        suspend fun snapshot(): List<LifeTask> = loadReport().tasks
    }
}
