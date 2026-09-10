package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerLifecycleRegisteredDrainTest {
    @Test
    fun `never-started worker drains without invoking runtime stop`() = runTest {
        val id = WorkerId("worker-never-started")
        val registry = WorkerRegistry()
        val controller = CountingController(id)
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            leases = object : WorkerLeaseInspector {
                override suspend fun inspect(workerId: WorkerId, at: Instant) = error("not expected")
            },
            healthGraph = HealthGraph(),
            now = { Instant.parse("2026-09-10T10:15:00Z") },
        )
        supervisor.attach(
            WorkerDescriptor(
                workerId = id,
                version = WorkerVersion(1),
                capabilities = setOf(CapabilityId("cognition.process")),
                maxConcurrency = 1,
                implementationFingerprint = "never-started/v1",
            ),
            controller,
        )

        val result = assertIs<WorkerDrainResult.Drained>(supervisor.drain(id))

        assertEquals(WorkerRuntimeState.STOPPED, result.entry.state)
        assertEquals(0, controller.stopAcceptingCalls)
        assertEquals(0, controller.stopCalls)
    }

    private class CountingController(override val workerId: WorkerId) : WorkerRuntimeController {
        var stopAcceptingCalls = 0
        var stopCalls = 0
        override suspend fun start() = Unit
        override suspend fun stopAcceptingNewWork() {
            stopAcceptingCalls += 1
        }
        override suspend fun stop() {
            stopCalls += 1
        }
    }
}
