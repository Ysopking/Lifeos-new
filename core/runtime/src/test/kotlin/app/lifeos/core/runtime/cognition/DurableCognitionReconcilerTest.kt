package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.IndexedTaskSnapshotRepository
import app.lifeos.core.model.task.TaskIndexReport
import app.lifeos.core.model.task.TaskLoadReport
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
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
                createdAt = photon.provenance.createdAt,
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
            createdAt = photon.provenance.createdAt,
            idempotencyKey = "failed-existing",
        )
        val tasks = SnapshotTaskRepository(snapshotExtras = listOf(failed))
        val coverage = CognitionCoverageIndex(MemoryCoverageRepository())
        coverage.markCovered(PhotonRevisionRef(photon.id, photon.revision))

        val result = reconciler(
            TestPhotonRepository(listOf(photon)),
            tasks,
            coverage = coverage,
        ).reconcile()

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

    @Test
    fun reconciliationRemainsCoveredWithFreshProcessState() = runTest {
        val photons = TestPhotonRepository(listOf(photon("restart", revision = 2)))
        val tasks = SnapshotTaskRepository()
        val coverageRepository = MemoryCoverageRepository()
        val first = reconciler(
            photons,
            tasks,
            coverage = CognitionCoverageIndex(coverageRepository),
        ).reconcile()
        val afterRestart = reconciler(
            photons,
            tasks,
            coverage = CognitionCoverageIndex(coverageRepository),
        ).reconcile()
        assertEquals(1, first.submitted)
        assertEquals(0, afterRestart.submitted)
        assertEquals(1, afterRestart.alreadyCovered)
        assertEquals(1, tasks.snapshot().size)
    }

    @Test
    fun boundedPassDefersWorkAndReplayOnlySubmitsRemainingPhotons() = runTest {
        val photons = TestPhotonRepository((1..5).map { photon("batch-$it", revision = 1) })
        val tasks = SnapshotTaskRepository()
        val reconciler = reconciler(photons, tasks, batchSize = 2)
        val first = reconciler.reconcile()
        val second = reconciler.reconcile()
        val third = reconciler.reconcile()
        assertEquals(listOf(2, 2, 1), listOf(first.submitted, second.submitted, third.submitted))
        assertEquals(listOf(3, 1, 0), listOf(first.deferred, second.deferred, third.deferred))
        assertEquals(5, tasks.snapshot().size)
        assertEquals(0, reconciler.reconcile().submitted)
    }

    @Test
    fun durableCapacityDefersThenRefillsAfterTerminalTask() = runTest {
        val photons = TestPhotonRepository((1..2).map { photon("capacity-$it", revision = 1) })
        val tasks = SnapshotTaskRepository()
        val now = Instant.parse("2026-09-11T10:00:00Z")
        val taskEngine = DurableTaskEngine(
            tasks = tasks,
            schedulerSignal = TaskSchedulerSignal { },
            now = { now },
        )
        val admission = DurableCognitionAdmissionController(
            tasks = tasks,
            taskEngine = taskEngine,
            maxActiveTasks = 1,
        )
        val cognition = ContinuousCognitionEngine(
            journal = InMemoryCognitiveEventJournal(),
            scheduler = CognitiveScheduler(),
            durableDispatcher = DurableCognitionDispatcher(
                taskEngine = taskEngine,
                admissionController = admission,
            ),
        )
        val reconciler = DurableCognitionReconciler(
            photons = photons,
            tasks = tasks,
            cognition = cognition,
            taskEngine = taskEngine,
            coverage = CognitionCoverageIndex(MemoryCoverageRepository()),
        )

        val firstPass = reconciler.reconcile()
        assertEquals(1, firstPass.submitted)
        assertEquals(1, firstPass.deferred)

        val firstTask = tasks.snapshot().single()
        val worker = WorkerId("test-worker")
        val claimed = assertNotNull(
            tasks.claim(
                id = firstTask.id,
                workerId = worker,
                acquiredAt = now.plusSeconds(1),
                leaseUntil = now.plusSeconds(30),
            )
        )
        assertNotNull(tasks.startExecution(claimed.id, worker, now.plusSeconds(2)))
        assertNotNull(
            tasks.finishExecution(
                id = claimed.id,
                workerId = worker,
                finalState = TaskState.COMPLETED,
                finishedAt = now.plusSeconds(3),
            )
        )

        val refillPass = reconciler.reconcile()
        assertEquals(1, refillPass.submitted)
        assertEquals(0, refillPass.deferred)
        assertEquals(2, tasks.snapshot().size)
    }

    @Test
    fun createdLegacyTaskResumesOriginalIdentityAfterCrashBeforeQueueTransition() = runTest {
        val photon = photon("created-gap", revision = 1)
        val tasks = SnapshotTaskRepository()
        val created = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            createdAt = photon.provenance.createdAt,
            idempotencyKey = "legacy-created-key",
        )
        tasks.create(created)
        val reconciler = reconciler(TestPhotonRepository(listOf(photon)), tasks)
        val recovered = reconciler.reconcile()
        assertEquals(1, recovered.resumedCreated)
        assertEquals(0, recovered.submitted)
        assertEquals(TaskState.QUEUED, tasks.get(created.id)?.state)
        assertEquals(created.id, tasks.snapshot().single().id)
        assertEquals(0, reconciler.reconcile().resumedCreated)
    }

    private fun reconciler(
        photons: PhotonRepository,
        tasks: IndexedTaskSnapshotRepository,
        batchSize: Int = 100,
        coverage: CognitionCoverageIndex = CognitionCoverageIndex(MemoryCoverageRepository()),
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
            taskEngine = taskEngine,
            coverage = coverage,
            maxSubmissionsPerPass = batchSize,
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

    private class MemoryCoverageRepository : CognitionCoverageRepository {
        private var snapshot: CognitionCoverageSnapshot? = null

        override suspend fun load(): CognitionCoverageSnapshot? = snapshot

        override suspend fun save(snapshot: CognitionCoverageSnapshot) {
            this.snapshot = snapshot
        }
    }

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
    ) : IndexedTaskSnapshotRepository, TaskRepository by delegate {
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

        override suspend fun activeCount(types: Set<TaskType>): Int =
            loadReport().tasks.count { task ->
                task.type in types && task.state !in TERMINAL_STATES
            }

        override suspend fun listByStates(
            types: Set<TaskType>,
            states: Set<TaskState>,
            limit: Int,
        ): List<LifeTask> {
            require(limit > 0)
            return loadReport().tasks.asSequence()
                .filter { it.type in types && it.state in states }
                .sortedWith(compareBy<LifeTask> { it.createdAt }.thenBy { it.id.value })
                .take(limit)
                .toList()
        }

        override suspend fun rebuildIndex(): TaskIndexReport {
            val all = loadReport().tasks
            return TaskIndexReport(
                formatVersion = 1,
                taskCount = all.size,
                activeTaskCount = all.count { it.state !in TERMINAL_STATES },
                idempotencyKeyCount = all.map { it.idempotencyKey }.distinct().size,
            )
        }

        suspend fun snapshot(): List<LifeTask> = loadReport().tasks

        private companion object {
            val TERMINAL_STATES = setOf(
                TaskState.COMPLETED,
                TaskState.SUPERSEDED,
                TaskState.FAILED,
                TaskState.CANCELLED,
            )
        }
    }
}