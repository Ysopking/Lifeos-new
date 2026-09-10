package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerLifecycleSignalOrderingTest {
    @Test
    fun `capacity violation precedes heartbeat mismatch deterministically`() = runTest {
        val id = WorkerId("worker-signal-order")
        val at = Instant.parse("2026-09-10T10:30:00Z")
        val registry = WorkerRegistry()
        val sink = Sink()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            leases = object : WorkerLeaseInspector {
                override suspend fun inspect(workerId: WorkerId, at: Instant) = WorkerLeaseSnapshot(
                    workerId,
                    listOf(TaskId("t1"), TaskId("t2")),
                    emptyList(),
                    at,
                )
            },
            healthGraph = HealthGraph(),
            signals = sink,
            now = { at },
        )
        supervisor.attach(
            WorkerDescriptor(
                id,
                WorkerVersion(1),
                setOf(CapabilityId("cognition.process")),
                1,
                "signal-order/v1",
            ),
            Controller(id),
        )
        supervisor.start(id)

        assertIs<WorkerHeartbeatResult.Inconsistent>(
            supervisor.observeHeartbeat(WorkerHeartbeat(id, at, reportedActiveWork = 0))
        )
        assertEquals(
            listOf(
                WorkerLifecycleSignalKind.LEASE_CAPACITY_EXCEEDED,
                WorkerLifecycleSignalKind.HEARTBEAT_LEASE_MISMATCH,
            ),
            sink.events.map { it.kind },
        )
    }

    private class Controller(override val workerId: WorkerId) : WorkerRuntimeController {
        override suspend fun start() = Unit
        override suspend fun stopAcceptingNewWork() = Unit
        override suspend fun stop() = Unit
    }

    private class Sink : WorkerLifecycleSignalSink {
        val events = mutableListOf<WorkerLifecycleSignal>()
        override suspend fun emit(signal: WorkerLifecycleSignal) {
            events += signal
        }
    }
}
