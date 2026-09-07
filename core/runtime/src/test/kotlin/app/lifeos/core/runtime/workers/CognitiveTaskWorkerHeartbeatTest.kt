package app.lifeos.core.runtime.workers

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
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.recovery.LeaseRecoveryService
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CognitiveTaskWorkerHeartbeatTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")
    private val workerId = WorkerId("heartbeat-worker")

    @Test
    fun longRunningFieldRenewsLeaseBeforeRecoveryCanClaimIt() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, photon)
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(
                listOf(
                    ForceField {
                        delay(45_000)
                        null
                    }
                )
            ),
            executor = InfluenceExecutor(),
            leaseDuration = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(10),
            now = { t0.plusMillis(testScheduler.currentTime) },
        )
        val recovery = LeaseRecoveryService(
            tasks = tasks,
            schedulerSignal = ConflatedTaskSchedulerSignal(),
            now = { t0.plusMillis(testScheduler.currentTime) },
        )

        val execution = async { worker.execute(claimed) }
        runCurrent()

        advanceTimeBy(35_000)
        runCurrent()

        val running = checkNotNull(tasks.get(claimed.id))
        assertEquals(TaskState.RUNNING, running.state)
        assertTrue(checkNotNull(running.leaseExpiresAt).isAfter(t0.plusSeconds(35)))
        assertEquals(0, recovery.recoverExpired().scanned)

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(TaskState.COMPLETED, execution.await().finalState)
        assertEquals(TaskState.COMPLETED, tasks.get(claimed.id)?.state)
    }

    @Test
    fun recoveryWinningOwnershipStopsOldWorkerWithoutCoroutineCancellationEscaping() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claimedTask(tasks, photon)
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(
                listOf(
                    ForceField {
                        delay(45_000)
                        null
                    }
                )
            ),
            executor = InfluenceExecutor(),
            leaseDuration = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(10),
            now = { t0.plusMillis(testScheduler.currentTime) },
        )

        val execution = async { worker.execute(claimed) }
        runCurrent()

        advanceTimeBy(5_000)
        val interrupted = checkNotNull(
            tasks.transition(
                claimed.id,
                TaskState.RUNNING,
                TaskState.INTERRUPTED,
                t0.plusSeconds(5),
            )
        )
        val recovering = checkNotNull(
            tasks.transition(
                interrupted.id,
                TaskState.INTERRUPTED,
                TaskState.RECOVERING,
                t0.plusSeconds(5),
            )
        )
        tasks.transition(
            recovering.id,
            TaskState.RECOVERING,
            TaskState.QUEUED,
            t0.plusSeconds(5),
        )

        advanceTimeBy(5_000)
        runCurrent()

        try {
            execution.await()
            fail("Expected lease ownership loss")
        } catch (error: Exception) {
            assertTrue(error !is CancellationException)
        }
        assertEquals(TaskState.QUEUED, tasks.get(claimed.id)?.state)
    }

    private suspend fun claimedTask(
        tasks: InMemoryTaskRepository,
        photon: Photon,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "heartbeat:${photon.id.value}:r${photon.revision}",
            createdAt = t0,
            updatedAt = t0,
        )
        tasks.create(task)
        tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, t0)
        return checkNotNull(
            tasks.claim(
                task.id,
                workerId,
                t0,
                t0.plusSeconds(30),
            )
        )
    }

    private fun photon() = Photon(
        content = "heartbeat test photon",
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
