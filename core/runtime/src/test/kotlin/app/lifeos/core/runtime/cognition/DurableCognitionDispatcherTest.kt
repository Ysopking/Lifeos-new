package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableCognitionDispatcherTest {
    private val budget = CognitiveWorkBudget(
        maxDurationMs = 1_000,
        maxModuleInvocations = 4,
        maxNewPhotons = 2,
        maxNetworkCalls = 0,
    )

    @Test
    fun acceptedDeltaBecomesDurableQueuedTask() = runTest {
        val tasks = InMemoryTaskRepository()
        var wakeCount = 0
        val taskEngine = DurableTaskEngine(
            tasks = tasks,
            schedulerSignal = TaskSchedulerSignal { wakeCount++ },
            now = { Instant.parse("2026-09-07T19:00:00Z") },
        )
        val scheduler = CognitiveScheduler()
        val engine = ContinuousCognitionEngine(
            journal = InMemoryCognitiveEventJournal(),
            scheduler = scheduler,
            durableDispatcher = DurableCognitionDispatcher(taskEngine),
        )
        val photonId = PhotonId("photon-1")
        val delta = PhotonDelta(
            deltaId = "delta-1",
            source = "test",
            photonId = photonId,
            revisionAfter = 3,
            type = PhotonDeltaType.CREATED,
            timestamp = Instant.parse("2026-09-07T19:00:00Z"),
        )

        val result = engine.submit(
            delta = delta,
            priority = CognitivePriority.USER_BLOCKING,
            salience = SalienceVector(relevance = 1.0, urgency = 1.0),
            targetModules = setOf("Gedankenmatrix"),
            budget = budget,
        )

        assertTrue(result.accepted)
        val taskId = assertNotNull(result.durableTaskId)
        val task = assertNotNull(tasks.findByIdempotencyKey(
            "cognition:delta-1:photon:photon-1:revision:3:pipeline:1"
        ))
        assertEquals(taskId, task.id.value)
        assertEquals(TaskState.QUEUED, task.state)
        assertEquals(TaskPriority.INTERACTIVE, task.priority)
        assertEquals(mapOf(photonId to 3L), task.inputPhotonRevisions)
        assertEquals(1, wakeCount)
        assertEquals(0, scheduler.size())
    }

    @Test
    fun repeatedDeltaReturnsSameDurableTask() = runTest {
        val tasks = InMemoryTaskRepository()
        val taskEngine = DurableTaskEngine(
            tasks = tasks,
            schedulerSignal = TaskSchedulerSignal { },
            now = { Instant.parse("2026-09-07T19:00:00Z") },
        )
        val engine = ContinuousCognitionEngine(
            journal = InMemoryCognitiveEventJournal(),
            scheduler = CognitiveScheduler(),
            durableDispatcher = DurableCognitionDispatcher(taskEngine),
        )
        val delta = PhotonDelta(
            deltaId = "delta-repeat",
            source = "test",
            photonId = PhotonId("photon-repeat"),
            revisionAfter = 1,
            type = PhotonDeltaType.UPDATED,
        )

        val first = engine.submit(
            delta,
            CognitivePriority.NORMAL,
            SalienceVector(relevance = 0.7),
            emptySet(),
            budget,
        )
        val second = engine.submit(
            delta,
            CognitivePriority.NORMAL,
            SalienceVector(relevance = 0.7),
            emptySet(),
            budget,
        )

        assertNotNull(first.durableTaskId)
        assertEquals(first.durableTaskId, second.durableTaskId)
        assertEquals(1, tasks.listRunnable(Instant.MAX, limit = 10).size)
    }
}
