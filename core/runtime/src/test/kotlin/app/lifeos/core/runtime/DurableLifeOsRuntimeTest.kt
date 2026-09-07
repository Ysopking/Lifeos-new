package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableProcessingPipeline
import java.time.Instant
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableLifeOsRuntimeTest {
    private val t0 = Instant.parse("2026-09-07T17:00:00Z")

    @Test
    fun startIngestAndStopUseDurablePipelineAndRuntimeState() = runTest {
        val pipeline = FakePipeline(t0)
        val bridge = DurableRuntimeStateBridge()
        val runtime = DurableLifeOsRuntime(
            scope = backgroundScope,
            pipeline = pipeline,
            stateBridge = bridge,
        )
        val photon = Photon(
            content = "durable runtime",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = t0,
            ),
        )

        runtime.start()
        runtime.start()
        runtime.ingest(photon)

        assertEquals(RuntimeStatus.RUNNING, runtime.state.value.status)
        assertEquals(1, pipeline.starts)
        assertEquals(listOf(photon.id), pipeline.submitted.map { it.id })

        runtime.stop()
        runCurrent()

        assertEquals(1, pipeline.stops)
        assertEquals(RuntimeStatus.STOPPED, runtime.state.value.status)
    }

    @Test
    fun startFailureMarksRuntimeFailed() = runTest {
        val pipeline = FakePipeline(t0, failStart = true)
        val bridge = DurableRuntimeStateBridge()
        val runtime = DurableLifeOsRuntime(
            scope = backgroundScope,
            pipeline = pipeline,
            stateBridge = bridge,
        )

        try {
            runtime.start()
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(RuntimeStatus.FAILED, runtime.state.value.status)
        assertTrue(runtime.state.value.lastFailure?.message?.contains("start failed") == true)
    }

    private class FakePipeline(
        private val now: Instant,
        private val failStart: Boolean = false,
    ) : DurableProcessingPipeline {
        var starts = 0
            private set
        var stops = 0
            private set
        val submitted = mutableListOf<Photon>()

        override fun start() {
            if (failStart) error("start failed")
            starts += 1
        }

        override suspend fun stop() {
            stops += 1
        }

        override suspend fun submitPhoton(
            photon: Photon,
            priority: TaskPriority,
        ): LifeTask {
            submitted += photon
            return LifeTask(
                type = TaskType.PROCESS_PHOTON,
                priority = priority,
                inputPhotonIds = setOf(photon.id),
                idempotencyKey = "fake:${photon.id.value}:${photon.revision}",
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
