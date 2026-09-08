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
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.field.FieldShadowProcessor
import app.lifeos.core.runtime.field.FieldShadowState
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class FieldShadowWorkerIsolationTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")
    private val workerId = WorkerId("shadow-worker")

    @Test
    fun `shadow exception is recorded but cannot fail successful durable task`() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = Photon(
            id = PhotonId("shadow-worker-photon"),
            content = "shadow isolation",
            provenance = Provenance("test", "test", now),
        )
        photons.save(photon)
        val claimed = claim(tasks, photon)
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(emptyList()),
            executor = InfluenceExecutor(),
            fieldShadowProcessor = FieldShadowProcessor { error("shadow exploded") },
            now = { now },
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(TaskState.COMPLETED, tasks.get(claimed.id)?.state)
        assertEquals(emptyList(), result.failures)
        val shadow = assertNotNull(result.fieldShadow)
        assertEquals(FieldShadowState.FAILED, shadow.state)
        assertEquals("shadow exploded", shadow.message)
    }

    private suspend fun claim(tasks: InMemoryTaskRepository, photon: Photon): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "shadow:${photon.id.value}:r${photon.revision}",
            createdAt = now,
            updatedAt = now,
        )
        tasks.create(task)
        tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, now)
        return checkNotNull(tasks.claim(task.id, workerId, now, now.plusSeconds(30)))
    }

    private class FakePhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]
        override suspend fun loadReport() = PhotonLoadReport(values.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = values.values.toList()
        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
