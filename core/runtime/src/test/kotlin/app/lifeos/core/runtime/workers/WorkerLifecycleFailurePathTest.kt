package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkerLifecycleFailurePathTest {
    private val at = Instant.parse("2026-09-10T09:30:00Z")

    @Test
    fun `drain admission failure keeps worker runnable and emits signal`() = runTest {
        val id = WorkerId("worker-drain-fail")
        val registry = WorkerRegistry()
        val signals = RecordingSignals()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            EmptyLeases,
            HealthGraph(),
            signals,
        ) { at }
        supervisor.attach(descriptor(id), FailingController(id, drainFailure = IllegalStateException("admission-fail")))
        supervisor.start(id)

        val failed = assertIs<WorkerDrainResult.Failed>(supervisor.drain(id))

        assertEquals(WorkerRuntimeState.READY, failed.entry.state)
        assertTrue(signals.values.any { it.kind == WorkerLifecycleSignalKind.DRAIN_START_FAILED })
    }

    @Test
    fun `physical stop failure leaves worker draining and never claims stopped`() = runTest {
        val id = WorkerId("worker-stop-fail")
        val registry = WorkerRegistry()
        val signals = RecordingSignals()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            EmptyLeases,
            HealthGraph(),
            signals,
        ) { at }
        supervisor.attach(descriptor(id), FailingController(id, stopFailure = IllegalStateException("stop-fail")))
        supervisor.start(id)

        val failed = assertIs<WorkerDrainResult.Failed>(supervisor.drain(id))

        assertEquals(WorkerRuntimeState.DRAINING, failed.entry.state)
        assertEquals(WorkerRuntimeState.DRAINING, registry.entry(id)?.state)
        assertTrue(signals.values.any { it.kind == WorkerLifecycleSignalKind.STOP_FAILED })
    }

    private fun descriptor(id: WorkerId) = WorkerDescriptor(
        workerId = id,
        version = WorkerVersion(1),
        capabilities = setOf(CapabilityId("cognition.process")),
        maxConcurrency = 1,
        implementationFingerprint = "${id.value}/v1",
    )

    private object EmptyLeases : WorkerLeaseInspector {
        override suspend fun inspect(workerId: WorkerId, at: Instant) = WorkerLeaseSnapshot(
            workerId = workerId,
            ownedTaskIds = emptyList(),
            unreadableEntries = emptyList(),
            capturedAt = at,
        )
    }

    private class FailingController(
        override val workerId: WorkerId,
        private val drainFailure: Exception? = null,
        private val stopFailure: Exception? = null,
    ) : WorkerRuntimeController {
        override suspend fun start() = Unit
        override suspend fun stopAcceptingNewWork() {
            drainFailure?.let { throw it }
        }
        override suspend fun stop() {
            stopFailure?.let { throw it }
        }
    }

    private class RecordingSignals : WorkerLifecycleSignalSink {
        val values = mutableListOf<WorkerLifecycleSignal>()
        override suspend fun emit(signal: WorkerLifecycleSignal) {
            values += signal
        }
    }
}
