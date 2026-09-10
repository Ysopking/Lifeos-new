package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthObservation
import app.lifeos.core.runtime.health.HealthState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerLifecycleCanaryGuardTest {
    @Test
    fun `unhealthy canary cannot be promoted after incumbent drain`() = runTest {
        val at = Instant.parse("2026-09-10T10:00:00Z")
        val primary = WorkerId("primary-health-guard")
        val canary = WorkerId("canary-health-guard")
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            EmptyLeases,
            graph,
            now = { at },
        )

        supervisor.attach(descriptor(primary, WorkerVersion(1)), Controller(primary))
        supervisor.start(primary)
        supervisor.stageCanary(primary, descriptor(canary, WorkerVersion(2)), Controller(canary))
        supervisor.start(canary)
        graph.record(
            HealthObservation(
                nodeId = descriptor(canary, WorkerVersion(2)).healthNodeId,
                state = HealthState.UNHEALTHY,
                observedAt = at,
                source = "test",
                message = "forced-unhealthy",
            )
        )
        supervisor.drain(primary)

        val result = assertIs<WorkerCanaryPromotionResult.Rejected>(
            supervisor.promoteCanary(primary, canary)
        )

        assertEquals("canary-not-healthy", result.reason)
        assertEquals(WorkerDeploymentRole.PRIMARY, supervisor.role(primary))
        assertEquals(WorkerDeploymentRole.CANARY, supervisor.role(canary))
    }

    private fun descriptor(id: WorkerId, version: WorkerVersion) = WorkerDescriptor(
        workerId = id,
        version = version,
        capabilities = setOf(CapabilityId("cognition.process")),
        maxConcurrency = 1,
        implementationFingerprint = "${id.value}/$version",
    )

    private object EmptyLeases : WorkerLeaseInspector {
        override suspend fun inspect(workerId: WorkerId, at: Instant) = WorkerLeaseSnapshot(
            workerId = workerId,
            ownedTaskIds = emptyList(),
            unreadableEntries = emptyList(),
            capturedAt = at,
        )
    }

    private class Controller(override val workerId: WorkerId) : WorkerRuntimeController {
        override suspend fun start() = Unit
        override suspend fun stopAcceptingNewWork() = Unit
        override suspend fun stop() = Unit
    }
}
