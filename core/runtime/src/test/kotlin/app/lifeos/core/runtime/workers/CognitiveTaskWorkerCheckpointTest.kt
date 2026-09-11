package app.lifeos.core.runtime.workers

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.checkpoint.CheckpointId
import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.checkpoint.SaveCheckpointResult
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.ForceField
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.ThoughtMatrixDurableState
import app.lifeos.core.runtime.ThoughtMatrixDurableStateCodec
import app.lifeos.core.runtime.ThoughtMatrixStateRepository
import app.lifeos.core.runtime.checkpoints.FieldProgressCheckpoint
import app.lifeos.core.runtime.checkpoints.FieldProgressCheckpointCodec
import app.lifeos.core.runtime.checkpoints.InMemoryCheckpointRepository
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CognitiveTaskWorkerCheckpointTest {
    private val t0 = Instant.parse("2026-09-07T20:00:00Z")
    private val workerId = WorkerId("checkpoint-worker")

    @Test
    fun retryResumesOnlyFieldsThatWereNotCheckpointedSuccessfully() = runTest {
        var now = t0
        val tasks = InMemoryTaskRepository()
        val checkpoints = InMemoryCheckpointRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val executions = IntArray(3)
        var secondFieldAttempts = 0
        val fieldList = listOf(
            ForceField {
                executions[0] += 1
                null
            },
            ForceField {
                executions[1] += 1
                secondFieldAttempts += 1
                if (secondFieldAttempts == 1) error("temporary")
                null
            },
            ForceField {
                executions[2] += 1
                null
            },
        )
        val claimed = claimedTask(tasks, photon, now)
        val worker = worker(tasks, photons, checkpoints, fieldList) { now }

        val first = worker.execute(claimed)

        assertEquals(TaskState.RETRY_WAIT, first.finalState)
        assertEquals(listOf(1, 1, 1), executions.toList())
        val firstCheckpoint = checkNotNull(checkpoints.latest(claimed.id))
        val firstProgress = FieldProgressCheckpointCodec.decode(firstCheckpoint.payload)
        assertEquals(setOf(0, 2), firstProgress.completedFieldIndexes)

        now = t0.plusSeconds(10)
        val queued = checkNotNull(
            tasks.transition(
                claimed.id,
                TaskState.RETRY_WAIT,
                TaskState.QUEUED,
                now,
            )
        )
        val reclaimed = checkNotNull(
            tasks.claim(
                queued.id,
                workerId,
                now,
                now.plusSeconds(30),
            )
        )

        val second = worker.execute(reclaimed)

        assertEquals(TaskState.COMPLETED, second.finalState)
        assertEquals(listOf(1, 2, 1), executions.toList())
        assertNull(checkpoints.latest(claimed.id))
        assertEquals(2, tasks.get(claimed.id)?.attempt)
    }

    @Test
    fun processRestartRestoresMatrixAndResumesAfterDurableFieldCheckpoint() = runTest {
        var now = t0
        val tasks = InMemoryTaskRepository()
        val checkpoints = InMemoryCheckpointRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val matrixState = CountingThoughtMatrixStateRepository()
        val firstMatrix = ThoughtMatrix(durableState = matrixState)
        var boundaryExecutions = 0
        val claimed = claimedTask(tasks, photon, now)
        val firstWorker = worker(
            tasks = tasks,
            photons = photons,
            checkpoints = checkpoints,
            fields = listOf(
                firstMatrix,
                RestartBoundaryField(
                    crash = true,
                    onRun = { boundaryExecutions += 1 },
                ),
            ),
        ) { now }

        assertFailsWith<CancellationException> {
            firstWorker.execute(claimed)
        }

        assertEquals(TaskState.INTERRUPTED, tasks.get(claimed.id)?.state)
        assertEquals(1, boundaryExecutions)
        assertEquals(1, matrixState.saveCount)
        val persistedCheckpoint = checkNotNull(checkpoints.latest(claimed.id))
        val persistedProgress = FieldProgressCheckpointCodec.decode(persistedCheckpoint.payload)
        assertEquals(setOf(0), persistedProgress.completedFieldIndexes)
        val persistedMatrix = checkNotNull(matrixState.state)
        val expectedFingerprint = persistedMatrix.v2Snapshot.contentFingerprint

        val restartedMatrix = ThoughtMatrix(durableState = matrixState)
        val restore = restartedMatrix.rehydrate()
        assertTrue(restore.restored)
        assertTrue(restartedMatrix.state.value.nodes.containsKey(photon.id))
        assertEquals(expectedFingerprint, restartedMatrix.v2Snapshot().contentFingerprint)

        now = t0.plusSeconds(2)
        val recovering = checkNotNull(
            tasks.transition(
                claimed.id,
                TaskState.INTERRUPTED,
                TaskState.RECOVERING,
                now,
            )
        )
        val queued = checkNotNull(
            tasks.transition(
                recovering.id,
                TaskState.RECOVERING,
                TaskState.QUEUED,
                now,
            )
        )
        val reclaimed = checkNotNull(
            tasks.claim(
                queued.id,
                workerId,
                now,
                now.plusSeconds(30),
            )
        )
        val restartedWorker = worker(
            tasks = tasks,
            photons = photons,
            checkpoints = checkpoints,
            fields = listOf(
                restartedMatrix,
                RestartBoundaryField(
                    crash = false,
                    onRun = { boundaryExecutions += 1 },
                ),
            ),
        ) { now }

        val result = restartedWorker.execute(reclaimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(2, boundaryExecutions)
        assertEquals(1, matrixState.saveCount)
        assertEquals(expectedFingerprint, restartedMatrix.v2Snapshot().contentFingerprint)
        assertNull(checkpoints.latest(claimed.id))
    }

    @Test
    fun incompatibleFieldSignatureIsIgnoredAndAllCurrentFieldsRun() = runTest {
        val tasks = InMemoryTaskRepository()
        val checkpoints = InMemoryCheckpointRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val executions = IntArray(2)
        val fields = listOf(
            ForceField { executions[0] += 1; null },
            ForceField { executions[1] += 1; null },
        )
        val claimed = claimedTask(tasks, photon, t0)
        checkpoints.save(
            TaskCheckpoint(
                taskId = claimed.id,
                sequence = 1,
                payload = FieldProgressCheckpointCodec.encode(
                    FieldProgressCheckpoint(
                        fieldSignature = "different-build-signature",
                        completedFieldIndexes = setOf(0),
                    )
                ),
                createdAt = t0,
            )
        )
        val worker = worker(tasks, photons, checkpoints, fields) { t0 }

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(listOf(1, 1), executions.toList())
        assertNull(checkpoints.latest(claimed.id))
    }

    @Test
    fun checkpointLookupFailureBecomesRetryableStorageFailure() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        var fieldExecutions = 0
        val claimed = claimedTask(tasks, photon, t0)
        val worker = worker(
            tasks = tasks,
            photons = photons,
            checkpoints = FailingCheckpointRepository(),
            fields = listOf(ForceField { fieldExecutions += 1; null }),
        ) { t0 }

        val result = worker.execute(claimed)

        assertEquals(TaskState.RETRY_WAIT, result.finalState)
        assertEquals(0, fieldExecutions)
        assertEquals(RuntimeFailureCategory.STORAGE, result.failures.single().category)
        assertTrue(result.failures.single().recoverable)
    }

    @Test
    fun lostLeasePreventsCheckpointWriteAfterFieldReturns() = runTest {
        val tasks = InMemoryTaskRepository()
        val checkpoints = InMemoryCheckpointRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, photon, t0)
        val field = ForceField {
            checkNotNull(
                tasks.interruptExecution(
                    id = claimed.id,
                    workerId = workerId,
                    interruptedAt = t0.plusSeconds(1),
                )
            )
            null
        }
        val worker = worker(tasks, photons, checkpoints, listOf(field)) { t0.plusSeconds(1) }

        try {
            worker.execute(claimed)
            fail("Expected ownership loss")
        } catch (error: Exception) {
            assertTrue(error !is CancellationException)
        }

        assertEquals(TaskState.INTERRUPTED, tasks.get(claimed.id)?.state)
        assertNull(checkpoints.latest(claimed.id))
    }

    private fun worker(
        tasks: InMemoryTaskRepository,
        photons: PhotonRepository,
        checkpoints: CheckpointRepository,
        fields: List<ForceField>,
        now: () -> Instant,
    ) = CognitiveTaskWorker(
        workerId = workerId,
        tasks = tasks,
        photons = photons,
        fields = StaticFieldRegistry(fields),
        executor = InfluenceExecutor(),
        checkpoints = checkpoints,
        now = now,
    )

    private suspend fun claimedTask(
        tasks: InMemoryTaskRepository,
        photon: Photon,
        acquiredAt: Instant,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "checkpoint:${photon.id.value}:r${photon.revision}",
            createdAt = acquiredAt,
            updatedAt = acquiredAt,
        )
        assertIs<app.lifeos.core.model.task.CreateTaskResult.Created>(tasks.create(task))
        val queued = checkNotNull(
            tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, acquiredAt)
        )
        return checkNotNull(
            tasks.claim(
                queued.id,
                workerId,
                acquiredAt,
                acquiredAt.plusSeconds(30),
            )
        )
    }

    private fun photon() = Photon(
        content = "checkpoint photon",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = t0,
        ),
    )

    private class RestartBoundaryField(
        private val crash: Boolean,
        private val onRun: () -> Unit,
    ) : ForceField {
        override suspend fun influence(photon: Photon): FieldInfluence? {
            onRun()
            if (crash) throw CancellationException("simulated-process-kill")
            return null
        }
    }

    private class CountingThoughtMatrixStateRepository : ThoughtMatrixStateRepository {
        var state: ThoughtMatrixDurableState? = null
            private set
        var saveCount: Int = 0
            private set

        override suspend fun save(state: ThoughtMatrixDurableState) {
            saveCount += 1
            this.state = ThoughtMatrixDurableStateCodec.decode(
                ThoughtMatrixDurableStateCodec.encode(state)
            )
        }

        override suspend fun load(): ThoughtMatrixDurableState? = state
    }

    private class FakePhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(photons.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }
    }

    private class FailingCheckpointRepository : CheckpointRepository {
        override suspend fun save(checkpoint: TaskCheckpoint): SaveCheckpointResult = error("unavailable")
        override suspend fun get(id: CheckpointId): TaskCheckpoint? = error("unavailable")
        override suspend fun latest(taskId: app.lifeos.core.model.task.TaskId): TaskCheckpoint? = error("unavailable")
        override suspend fun list(taskId: app.lifeos.core.model.task.TaskId, limit: Int): List<TaskCheckpoint> = error("unavailable")
        override suspend fun delete(id: CheckpointId): Boolean = error("unavailable")
    }
}
