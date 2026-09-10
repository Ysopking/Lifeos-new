package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthObservation
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkerRegistryTest {
    private val capability = CapabilityId("cognition.process")

    @Test
    fun `registration is idempotent but conflicting descriptors are explicit`() {
        val registry = WorkerRegistry()
        val v1 = descriptor("worker-a", WorkerVersion(1, 0, 0), "impl-a")

        assertIs<WorkerRegistrationResult.Registered>(
            registry.register(v1, WorkerRuntimeState.READY)
        )
        assertIs<WorkerRegistrationResult.AlreadyRegistered>(
            registry.register(v1, WorkerRuntimeState.STOPPED)
        )

        val sameVersionConflict = assertIs<WorkerRegistrationResult.Rejected>(
            registry.register(v1.copy(implementationFingerprint = "impl-b"))
        )
        assertEquals(WorkerRegistrationConflict.VERSION_CONFLICT, sameVersionConflict.conflict)

        val stale = assertIs<WorkerRegistrationResult.Rejected>(
            registry.register(v1.copy(version = WorkerVersion(0, 9, 9)))
        )
        assertEquals(WorkerRegistrationConflict.STALE_VERSION, stale.conflict)

        val liveUpgrade = assertIs<WorkerRegistrationResult.Rejected>(
            registry.register(v1.copy(version = WorkerVersion(2, 0, 0), implementationFingerprint = "impl-v2"))
        )
        assertEquals(WorkerRegistrationConflict.REPLACEMENT_REQUIRES_STOP, liveUpgrade.conflict)
    }

    @Test
    fun `newer implementation can replace only an explicitly stopped worker`() {
        val registry = WorkerRegistry()
        val v1 = descriptor("worker-a", WorkerVersion(1, 0, 0), "impl-v1")
        registry.register(v1, WorkerRuntimeState.READY)
        registry.updateState(v1.workerId, WorkerRuntimeState.STOPPED)

        val replacement = assertIs<WorkerRegistrationResult.Registered>(
            registry.register(
                v1.copy(version = WorkerVersion(2, 0, 0), implementationFingerprint = "impl-v2"),
                WorkerRuntimeState.REGISTERED,
            )
        )

        assertEquals(WorkerVersion(1, 0, 0), replacement.replacedStoppedVersion)
        assertEquals(WorkerVersion(2, 0, 0), registry.entry(v1.workerId)?.descriptor?.version)
        assertEquals(WorkerRuntimeState.REGISTERED, registry.entry(v1.workerId)?.state)
    }

    @Test
    fun `candidate query filters capability state saturation and live health`() = runBlocking {
        val now = Instant.parse("2026-09-10T08:00:00Z")
        val health = HealthGraph(now = { now })
        val registry = WorkerRegistry(health)
        val healthy = descriptor("worker-healthy", WorkerVersion(1), "healthy")
        val degraded = descriptor("worker-degraded", WorkerVersion(1), "degraded")
        val saturated = descriptor("worker-saturated", WorkerVersion(1), "saturated")
        val wrongCapability = descriptor(
            "worker-other",
            WorkerVersion(1),
            "other",
            capabilities = setOf(CapabilityId("other.capability")),
        )

        listOf(healthy, degraded, saturated, wrongCapability).forEach { descriptor ->
            registry.register(descriptor, WorkerRuntimeState.READY)
            health.register(descriptor.healthNodeId, HealthScope.WORKER)
        }
        health.recordHealthy(healthy.healthNodeId, source = "test")
        health.record(
            HealthObservation(
                nodeId = degraded.healthNodeId,
                state = HealthState.DEGRADED,
                observedAt = now,
                source = "test",
            )
        )
        health.recordHealthy(saturated.healthNodeId, source = "test")
        health.recordHealthy(wrongCapability.healthNodeId, source = "test")
        registry.updateLoad(saturated.workerId, activeWork = 1)

        val result = registry.query(
            WorkerCandidateQuery(
                capabilityId = capability,
                allowDegradedHealth = false,
                allowUnknownHealth = false,
            )
        )

        assertEquals(listOf("worker-healthy"), result.candidates.map { it.entry.descriptor.workerId.value })
        val excluded = result.excluded.associateBy { it.entry.descriptor.workerId.value }
        assertTrue(WorkerExclusionReason.HEALTH_NOT_ELIGIBLE in excluded.getValue("worker-degraded").reasons)
        assertTrue(WorkerExclusionReason.SATURATED in excluded.getValue("worker-saturated").reasons)
        assertTrue(WorkerExclusionReason.CAPABILITY_MISSING in excluded.getValue("worker-other").reasons)
    }

    @Test
    fun `candidate ordering is deterministic across registration order`() = runBlocking {
        fun registryWith(order: List<String>): WorkerRegistry {
            val registry = WorkerRegistry()
            order.forEach { id ->
                registry.register(descriptor(id, WorkerVersion(1), "impl-$id"), WorkerRuntimeState.READY)
            }
            registry.updateLoad(WorkerId("worker-b"), 1)
            return registry
        }

        val first = registryWith(listOf("worker-c", "worker-b", "worker-a"))
        val second = registryWith(listOf("worker-a", "worker-b", "worker-c"))
        val query = WorkerCandidateQuery(capability)

        val firstIds = first.query(query).candidates.map { it.entry.descriptor.workerId.value }
        val secondIds = second.query(query).candidates.map { it.entry.descriptor.workerId.value }

        assertEquals(listOf("worker-a", "worker-c"), firstIds)
        assertEquals(firstIds, secondIds)
    }

    @Test
    fun `snapshot round trip is canonical and preserves revision`() {
        val source = WorkerRegistry()
        val b = descriptor("worker-b", WorkerVersion(1, 2, 0), "b")
        val a = descriptor("worker-a", WorkerVersion(2, 0, 1), "a")
        source.register(b, WorkerRuntimeState.READY)
        source.register(a, WorkerRuntimeState.REGISTERED)
        source.updateLoad(b.workerId, 1)

        val snapshot = source.snapshot()
        assertEquals(listOf("worker-a", "worker-b"), snapshot.entries.map { it.descriptor.workerId.value })

        val restored = WorkerRegistry()
        restored.rehydrate(snapshot)
        assertEquals(snapshot, restored.snapshot())

        assertFailsWith<IllegalArgumentException> {
            restored.rehydrate(snapshot)
        }
    }

    @Test
    fun `stopped-only removal protects live topology`() {
        val registry = WorkerRegistry()
        val worker = descriptor("worker-a", WorkerVersion(1), "impl")
        registry.register(worker, WorkerRuntimeState.READY)

        assertFailsWith<IllegalArgumentException> {
            registry.removeStopped(worker.workerId)
        }
        registry.updateState(worker.workerId, WorkerRuntimeState.STOPPED)
        assertEquals(worker.workerId, registry.removeStopped(worker.workerId)?.descriptor?.workerId)
        assertEquals(null, registry.entry(worker.workerId))
    }

    private fun descriptor(
        id: String,
        version: WorkerVersion,
        fingerprint: String,
        capabilities: Set<CapabilityId> = setOf(capability),
    ): WorkerDescriptor = WorkerDescriptor(
        workerId = WorkerId(id),
        version = version,
        capabilities = capabilities,
        maxConcurrency = 1,
        implementationFingerprint = fingerprint,
        healthNodeId = HealthNodeId("worker:$id"),
    )
}
