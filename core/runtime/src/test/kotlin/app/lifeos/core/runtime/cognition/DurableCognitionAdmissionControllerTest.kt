package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.IndexedTaskSnapshotRepository
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskSnapshotRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DurableCognitionAdmissionControllerTest {
    @Test
    fun concurrentSubmissionsCannotOversubscribeDurableCapacity() = runTest {
        val tasks = InMemoryTaskRepository()
        val engine = taskEngine(tasks)
        val admission = DurableCognitionAdmissionController(
            tasks = tasks,
            taskEngine = engine,
            maxActiveTasks = 2,
        )

        val results = coroutineScope {
            (1..16).map { index ->
                async { admission.submit(draft(index)) }
            }.awaitAll()
        }

        assertEquals(2, results.count { it != null })
        assertEquals(2, tasks.loadReport().tasks.size)
        assertEquals(0, admission.availableCapacity())
    }

    @Test
    fun fullCapacityStillReturnsExistingIdempotentTask() = runTest {
        val tasks = InMemoryTaskRepository()
        val engine = taskEngine(tasks)
        val admission = DurableCognitionAdmissionController(
            tasks = tasks,
            taskEngine = engine,
            maxActiveTasks = 1,
        )

        val first = assertNotNull(admission.submit(draft(1)))
        val duplicate = assertNotNull(admission.submit(draft(1)))
        val overflow = admission.submit(draft(2))

        assertEquals(first.id, duplicate.id)
        assertNull(overflow)
        assertEquals(1, tasks.loadReport().tasks.size)
    }

    @Test
    fun indexedAdmissionHotPathDoesNotLoadFullTaskSnapshot() = runTest {
        val delegate = InMemoryTaskRepository()
        var fullSnapshotReads = 0
        val indexedTasks = object : IndexedTaskSnapshotRepository by delegate {
            override suspend fun loadReport() = delegate.loadReport().also {
                fullSnapshotReads += 1
            }
        }
        val engine = taskEngine(indexedTasks)
        val admission = DurableCognitionAdmissionController(
            tasks = indexedTasks,
            taskEngine = engine,
            maxActiveTasks = 2,
        )

        assertNotNull(admission.submit(draft(1)))
        assertNotNull(admission.submit(draft(1)))
        assertEquals(1, admission.availableCapacity())
        assertEquals(0, fullSnapshotReads)
    }

    @Test
    fun taskLedgerReadFailureFailsClosed() = runTest {
        val delegate = InMemoryTaskRepository()
        val failingTasks = object : IndexedTaskSnapshotRepository by delegate {
            override suspend fun activeCount(types: Set<TaskType>): Int =
                error("ledger-index-unreadable")
        }
        val admission = DurableCognitionAdmissionController(
            tasks = failingTasks,
            taskEngine = taskEngine(failingTasks),
            maxActiveTasks = 1,
        )

        assertFailsWith<IllegalStateException> {
            admission.submit(draft(1))
        }
        assertEquals(0, delegate.loadReport().tasks.size)
    }

    private fun taskEngine(tasks: TaskSnapshotRepository): DurableTaskEngine = DurableTaskEngine(
        tasks = tasks,
        schedulerSignal = TaskSchedulerSignal { },
        now = { Instant.parse("2026-09-11T05:00:00Z") },
    )

    private fun draft(index: Int): TaskDraft {
        val photonId = PhotonId("photon-$index")
        return TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photonId),
            inputPhotonRevisions = mapOf(photonId to 1L),
            idempotencyKey = "cognition:test:$index",
        )
    }
}
