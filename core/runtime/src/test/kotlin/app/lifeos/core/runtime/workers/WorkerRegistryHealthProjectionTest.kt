package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRegistryHealthProjectionTest {
    @Test
    fun `candidate health is read live from health graph and never rehydrated as registry truth`() = runBlocking {
        val graph = HealthGraph()
        val registry = WorkerRegistry(graph)
        val descriptor = WorkerDescriptor(
            workerId = WorkerId("worker-live-health"),
            version = WorkerVersion(1),
            capabilities = setOf(CapabilityId("cognition.process")),
            maxConcurrency = 1,
            implementationFingerprint = "worker-live-health/v1",
        )
        registry.register(descriptor, WorkerRuntimeState.READY)
        graph.register(descriptor.healthNodeId, HealthScope.WORKER)

        val before = registry.query(WorkerCandidateQuery(CapabilityId("cognition.process")))
        assertEquals(HealthState.UNKNOWN, before.candidates.single().health.state)

        graph.recordHealthy(descriptor.healthNodeId, source = "test")
        val after = registry.query(WorkerCandidateQuery(CapabilityId("cognition.process")))
        assertEquals(HealthState.HEALTHY, after.candidates.single().health.state)

        val restored = WorkerRegistry()
        restored.rehydrate(registry.snapshot())
        val restoredView = restored.query(WorkerCandidateQuery(CapabilityId("cognition.process")))
        assertEquals(HealthState.UNKNOWN, restoredView.candidates.single().health.state)
    }
}
