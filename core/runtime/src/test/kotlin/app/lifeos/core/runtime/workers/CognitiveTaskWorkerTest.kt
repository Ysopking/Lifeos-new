package app.lifeos.core.runtime.workers

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.ForceField
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class CognitiveTaskWorkerTest {
    private val t0 = Instant.parse("2026-09-07T15:00:00Z")
    private val workerId = WorkerId("cognitive-worker-0")

    @Test
    fun healthyProcessPhotonTaskCompletes() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, workerId, photon.id)
        val worker = worker(
            tasks,
            photons,
            listOf(
                ForceField { input ->
                    FieldInfluence(
                        module = "healthy",
                        photonId = input.id,
                        type = "TEST",
                        deltaEnergy = 0.1,
                        confidence = 1.0,
                        explanation = "ok",
                    )
                }
            ),
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(1, result.influences.size)
        assertEquals(TaskState.COMPLETED, tasks.get(claimed.id)?.state)
    }

    @Test
    fun fieldFailureMarksTaskFailedButHealthyFieldStillRuns() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, workerId, photon.id)
        val worker = worker(
            tasks,
            photons,
            listOf(
                ForceField { error("broken field") },
                ForceField { input ->
                    FieldInfluence(
                        module = "healthy",
                        photonId = input.id,
                        type = "TEST",
                        deltaEnergy = 0.1,
                        confidence = 1.0,
                        explanation = "still ran",
                    )
                },
            ),
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.FAILED, result.finalState)
        assertEquals(1, result.influences.size)
        assertEquals(1, result.failures.size)
        assertEquals(RuntimeFailureCategory.FIELD, result.failures.single().category)
    }

    @Test
    fun missingInputPhotonFailsTaskAsStorageFailure() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val missingId = PhotonId.new()
        val claimed = claimedTask(tasks, workerId, missingId)
        val worker = worker(tasks, photons, emptyList())

        val result = worker.execute(claimed)

        assertEquals(TaskState.FAILED, result.finalState)
        assertEquals(RuntimeFailureCategory.STORAGE, result.failures.single().category)
    }

    @Test
    fun cancellationMarksTaskInterruptedAndPropagates() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, workerId, photon.id)
        val worker = worker(
            tasks,
            photons,
            listOf(ForceField { throw CancellationException("stop") }),
        )

        try {
            worker.execute(claimed)
            fail("Expected CancellationException")
        } catch (cancelled: CancellationException) {
            assertEquals("stop", cancelled.message)
        }
        assertEquals(TaskState.INTERRUPTED, tasks.get(claimed.id)?.state)
    }

    @Test
    fun workerRejectsTaskClaimedByAnotherWorker() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, WorkerId("other-worker"), photon.id)
        val worker = worker(tasks, photons, emptyList())

        try {
            worker.execute(claimed)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: the worker must never execute another worker's lease.
        }
    }

    @Test
    fun workerRejectsExpiredLease() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(
            tasks = tasks,
            owner = workerId,
            photonId = photon.id,
            acquiredAt = t0.minusSeconds(60),
            leaseUntil = t0.minusSeconds(30),
        )
        val worker = worker(tasks, photons, emptyList())

        try {
            worker.execute(claimed)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: expired claims must never start execution.
        }
        assertEquals(TaskState.CLAIMED, tasks.get(claimed.id)?.state)
    }

    private fun worker(
        tasks: InMemoryTaskRepository,
        photons: PhotonRepository,
        fields: List<ForceField>,
    ) = CognitiveTaskWorker(
        workerId = workerId,
        tasks = tasks,
        photons = photons,
        fields = StaticFieldRegistry(fields),
        executor = InfluenceExecutor(),
        now = { t0 },
    )

    private suspend fun claimedTask(
        tasks: InMemoryTaskRepository,
        owner: WorkerId,
        photonId: PhotonId,
        acquiredAt: Instant = t0,
        leaseUntil: Instant = t0.plusSeconds(30),
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photonId),
            idempotencyKey = "process:${photonId.value}:r1",
            createdAt = acquiredAt,
            updatedAt = acquiredAt,
        )
        tasks.create(task)
        tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, acquiredAt)
        return checkNotNull(tasks.claim(task.id, owner, acquiredAt, leaseUntil))
    }

    private fun photon() = Photon(
        content = "test photon",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = t0,
        ),
    )

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
}
