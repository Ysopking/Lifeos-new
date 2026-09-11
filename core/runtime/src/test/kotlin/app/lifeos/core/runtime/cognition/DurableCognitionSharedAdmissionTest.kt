package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DurableCognitionSharedAdmissionTest {
    private val now = Instant.parse("2026-09-11T05:20:00Z")

    @Test
    fun independentControllersOnSameTaskEngineCannotOversubscribeOneSlot() = runTest {
        val tasks = InMemoryTaskRepository()
        val engine = DurableTaskEngine(tasks, TaskSchedulerSignal { }, now = { now })
        val first = DurableCognitionAdmissionController(tasks, engine, maxActiveTasks = 1)
        val second = DurableCognitionAdmissionController(tasks, engine, maxActiveTasks = 1)

        val admitted = coroutineScope {
            listOf(
                async { first.submit(processDraft("a")) },
                async { second.submit(processDraft("b")) },
            ).awaitAll()
        }

        assertEquals(1, admitted.count { it != null })
        assertEquals(1, tasks.loadReport().tasks.size)
    }

    @Test
    fun feedbackReprocessingCannotBypassFullDurableCognitionCapacity() = runTest {
        val tasks = InMemoryTaskRepository()
        val engine = DurableTaskEngine(tasks, TaskSchedulerSignal { }, now = { now })
        val admission = DurableCognitionAdmissionController(tasks, engine, maxActiveTasks = 1)
        assertNotNull(admission.submit(processDraft("occupied")))

        val photons = TestPhotonRepository()
        val photon = Photon(
            id = PhotonId("feedback"),
            revision = 1,
            content = "feedback",
            provenance = Provenance("test", "test", now),
        )
        photons.save(photon)
        val sink = DurableCognitiveTriggerSink(
            journal = InMemoryCognitiveTriggerSink(),
            photons = photons,
            taskEngine = engine,
            admissionController = admission,
        )

        val accepted = sink.emit(
            CognitiveTrigger(
                id = "feedback-trigger",
                type = CognitiveTriggerType.REEVALUATE,
                sourceTaskId = TaskId("source"),
                photonId = photon.id,
                reason = "test-backpressure",
                createdAt = now,
            )
        )

        assertFalse(accepted)
        assertEquals(1, tasks.loadReport().tasks.size)
        assertEquals(emptyList(), sink.snapshot())
    }

    private fun processDraft(suffix: String): TaskDraft {
        val photonId = PhotonId("photon-$suffix")
        return TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photonId),
            inputPhotonRevisions = mapOf(photonId to 1L),
            idempotencyKey = "cognition:$suffix",
        )
    }

    private class TestPhotonRepository : PhotonRepository {
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
