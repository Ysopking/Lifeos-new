package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DurableCognitivePipelineTest {
    private val t0 = Instant.parse("2026-09-07T16:00:00Z")

    @Test
    fun samePhotonRevisionMapsToSameLogicalTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = ConflatedTaskSchedulerSignal()
        val pipeline = pipeline(backgroundScope, repository, signal)
        val photon = photon()

        val first = pipeline.submitPhoton(photon)
        val second = pipeline.submitPhoton(photon)

        assertEquals(first.id, second.id)
        assertEquals(TaskState.QUEUED, second.state)
        assertEquals(TaskPriority.INTERACTIVE, second.priority)
        assertEquals(setOf(photon.id), second.inputPhotonIds)
    }

    @Test
    fun newPhotonRevisionCreatesNewLogicalTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = ConflatedTaskSchedulerSignal()
        val pipeline = pipeline(backgroundScope, repository, signal)
        val revisionOne = photon()
        val revisionTwo = revisionOne.copy(revision = 2)

        val first = pipeline.submitPhoton(revisionOne)
        val second = pipeline.submitPhoton(revisionTwo)

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
    }

    private fun pipeline(
        scope: CoroutineScope,
        repository: InMemoryTaskRepository,
        signal: ConflatedTaskSchedulerSignal,
    ): DurableCognitivePipeline {
        val engine = DurableTaskEngine(repository, signal) { t0 }
        val scheduler = TaskScheduler(
            tasks = repository,
            workerId = WorkerId("pipeline-test-worker"),
            dispatcher = ClaimedTaskDispatcher { },
            now = { t0 },
        )
        val loop = TaskSchedulerLoop(
            scope = scope,
            scheduler = scheduler,
            wakeSource = signal,
        )
        return DurableCognitivePipeline(engine, loop)
    }

    private fun photon() = Photon(
        content = "pipeline test",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = t0,
        ),
    )
}
