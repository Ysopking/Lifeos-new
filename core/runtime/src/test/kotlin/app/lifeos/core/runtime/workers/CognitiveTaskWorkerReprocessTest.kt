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
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CognitiveTaskWorkerReprocessTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")
    private val workerId = WorkerId("reprocess-worker")

    @Test
    fun reprocessPhotonRunsThroughSameFieldPipeline() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = Photon(
            revision = 2,
            content = "reprocess me",
            provenance = Provenance("test", "test", t0),
        )
        photons.save(photon)
        val task = LifeTask(
            type = TaskType.REPROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "reprocess:${photon.id.value}:r2",
            createdAt = t0,
            updatedAt = t0,
        )
        tasks.create(task)
        tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, t0)
        val claimed = checkNotNull(
            tasks.claim(task.id, workerId, t0, t0.plusSeconds(30))
        )
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(
                listOf(
                    ForceField { input ->
                        FieldInfluence(
                            module = "reprocess-test",
                            photonId = input.id,
                            type = "REEVALUATE",
                            deltaEnergy = 0.25,
                            confidence = 0.9,
                            explanation = "reprocessed",
                            occurredAt = t0,
                        )
                    }
                )
            ),
            executor = InfluenceExecutor(),
            now = { t0 },
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals("reprocess-test", result.influences.single().module)
        assertEquals(TaskState.COMPLETED, tasks.get(task.id)?.state)
    }

    private class FakePhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(values.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
