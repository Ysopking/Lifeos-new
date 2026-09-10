package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerLifecycleRoleGuardTest {
    @Test
    fun `retired worker cannot restart after canary promotion`() = runTest {
        val primary = WorkerId("role-primary")
        val canary = WorkerId("role-canary")
        val at = Instant.parse("2026-09-10T10:45:00Z")
        val registry = WorkerRegistry()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            EmptyLeases,
            HealthGraph(),
            now = { at },
        )
        supervisor.attach(descriptor(primary, WorkerVersion(1)), Controller(primary))
        supervisor.start(primary)
        supervisor.stageCanary(primary, descriptor(canary, WorkerVersion(2)), Controller(canary))
        supervisor.start(canary)
        supervisor.drain(primary)
        assertIs<WorkerCanaryPromotionResult.Promoted>(supervisor.promoteCanary(primary, canary))

        val restart = assertIs<WorkerStartResult.Rejected>(supervisor.start(primary))
        assertEquals("retired-worker", restart.reason)
    }

    private fun descriptor(id: WorkerId, version: WorkerVersion) = WorkerDescriptor(
        id,
        version,
        setOf(CapabilityId("cognition.process")),
        1,
        "${id.value}/$version",
    )

    private object EmptyLeases : WorkerLeaseInspector {
        override suspend fun inspect(workerId: WorkerId, at: Instant) = WorkerLeaseSnapshot(
            workerId,
            emptyList(),
            emptyList(),
            at,
        )
    }

    private class Controller(override val workerId: WorkerId) : WorkerRuntimeController {
        override suspend fun start() = Unit
        override suspend fun stopAcceptingNewWork() = Unit
        override suspend fun stop() = Unit
    }
}
