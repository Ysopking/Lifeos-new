package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableCognitiveTriggerSinkTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")

    @Test
    fun reevaluationTriggerCreatesPinnedDurableReprocessTask() = runTest {
        val photons = FakePhotonRepository()
        val photon = photon(revision = 4)
        photons.save(photon)
        val tasks = InMemoryTaskRepository()
        var wakes = 0
        val sink = DurableCognitiveTriggerSink(
            journal = InMemoryCognitiveTriggerSink(),
            photons = photons,
            taskEngine = DurableTaskEngine(
                tasks = tasks,
                schedulerSignal = TaskSchedulerSignal { wakes++ },
                now = { t0 },
            ),
        )
        val trigger = trigger(CognitiveTriggerType.REEVALUATE, photon.id)

        assertTrue(sink.emit(trigger))

        val task = tasks.listRunnable(t0, limit = 10).single()
        assertEquals(TaskType.REPROCESS_PHOTON, task.type)
        assertEquals(TaskPriority.HIGH, task.priority)
        assertEquals(mapOf(photon.id to 4L), task.inputPhotonRevisions)
        assertEquals(1, wakes)
        assertEquals(listOf(trigger), sink.snapshot())
    }

    @Test
    fun repeatedTriggerReusesSameDurableTask() = runTest {
        val photons = FakePhotonRepository()
        val photon = photon(revision = 2)
        photons.save(photon)
        val tasks = InMemoryTaskRepository()
        val sink = DurableCognitiveTriggerSink(
            journal = InMemoryCognitiveTriggerSink(),
            photons = photons,
            taskEngine = DurableTaskEngine(tasks, TaskSchedulerSignal { }, now = { t0 }),
        )
        val trigger = trigger(CognitiveTriggerType.CONVERGENCE, photon.id)

        assertTrue(sink.emit(trigger))
        assertEquals(false, sink.emit(trigger))

        assertEquals(1, tasks.listRunnable(t0, limit = 10).size)
        assertEquals(1, sink.snapshot().size)
    }

    @Test
    fun recoveryAndQuarantineTriggersRemainPassive() = runTest {
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val tasks = InMemoryTaskRepository()
        val sink = DurableCognitiveTriggerSink(
            journal = InMemoryCognitiveTriggerSink(),
            photons = photons,
            taskEngine = DurableTaskEngine(tasks, TaskSchedulerSignal { }, now = { t0 }),
        )

        sink.emit(trigger(CognitiveTriggerType.RECOVERY, photon.id, id = "recovery"))
        sink.emit(trigger(CognitiveTriggerType.QUARANTINE_REVIEW, photon.id, id = "quarantine"))

        assertEquals(0, tasks.listRunnable(t0, limit = 10).size)
        assertEquals(2, sink.snapshot().size)
    }

    private fun trigger(
        type: CognitiveTriggerType,
        photonId: PhotonId,
        id: String = "trigger-1",
    ) = CognitiveTrigger(
        id = id,
        type = type,
        sourceTaskId = app.lifeos.core.model.task.TaskId("source-task"),
        photonId = photonId,
        reason = "test",
        createdAt = t0,
    )

    private fun photon(revision: Long = 1) = Photon(
        revision = revision,
        content = "trigger photon",
        provenance = Provenance("test", "test", t0),
    )

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
