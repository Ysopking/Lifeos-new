package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkerLifecycleSupervisorTest {
    private val baseTime = Instant.parse("2026-09-10T09:00:00Z")

    @Test
    fun `drain never stops worker while durable leases remain`() = runTest {
        val workerId = WorkerId("worker-drain")
        val controller = FakeController(workerId)
        val leaseInspector = FakeLeaseInspector(baseTime).apply {
            owned[workerId] = listOf(TaskId("task-b"), TaskId("task-a"))
        }
        val registry = WorkerRegistry()
        val supervisor = WorkerLifecycleSupervisor(
            registry = registry,
            leases = leaseInspector,
            healthGraph = HealthGraph(),
            now = { baseTime },
        )

        supervisor.attach(descriptor(workerId), controller)
        assertIs<WorkerStartResult.Started>(supervisor.start(workerId))

        val waiting = assertIs<WorkerDrainResult.WaitingForLeases>(supervisor.drain(workerId))
        assertEquals(listOf("task-a", "task-b"), waiting.ownedTaskIds.map { it.value })
        assertEquals(WorkerRuntimeState.DRAINING, registry.entry(workerId)?.state)
        assertEquals(1, controller.stopAcceptingCalls)
        assertEquals(0, controller.stopCalls)

        leaseInspector.owned[workerId] = emptyList()
        val drained = assertIs<WorkerDrainResult.Drained>(supervisor.drain(workerId))
        assertEquals(WorkerRuntimeState.STOPPED, drained.entry.state)
        assertEquals(1, controller.stopAcceptingCalls)
        assertEquals(1, controller.stopCalls)
    }

    @Test
    fun `incomplete lease inspection blocks physical stop`() = runTest {
        val workerId = WorkerId("worker-corrupt-leases")
        val controller = FakeController(workerId)
        val leaseInspector = FakeLeaseInspector(baseTime).apply {
            unreadable[workerId] = listOf("vault-b", "vault-a")
        }
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = WorkerLifecycleSupervisor(registry, leaseInspector, graph, signals) { baseTime }

        supervisor.attach(descriptor(workerId), controller)
        supervisor.start(workerId)
        val blocked = assertIs<WorkerDrainResult.Blocked>(supervisor.drain(workerId))

        assertEquals(listOf("vault-a", "vault-b"), blocked.unreadableEntries)
        assertEquals(WorkerRuntimeState.DRAINING, registry.entry(workerId)?.state)
        assertEquals(0, controller.stopCalls)
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.LEASE_INSPECTION_INCOMPLETE })
        assertTrue((graph.node(descriptor(workerId).healthNodeId)?.totalFailures ?: 0L) > 0L)
    }

    @Test
    fun `heartbeat reconciles registry load from durable leases and emits mismatches`() = runTest {
        val workerId = WorkerId("worker-heartbeat")
        val leaseInspector = FakeLeaseInspector(baseTime).apply {
            owned[workerId] = listOf(TaskId("lease-1"), TaskId("lease-2"))
        }
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = WorkerLifecycleSupervisor(registry, leaseInspector, graph, signals) { baseTime }

        supervisor.attach(descriptor(workerId, maxConcurrency = 1), FakeController(workerId))
        supervisor.start(workerId)
        val result = assertIs<WorkerHeartbeatResult.Inconsistent>(
            supervisor.observeHeartbeat(
                WorkerHeartbeat(workerId, baseTime.plusSeconds(5), reportedActiveWork = 0)
            )
        )

        assertEquals(2, result.actualActiveWork)
        assertEquals(2, result.entry.load.activeWork)
        assertEquals(WorkerRuntimeState.BUSY, result.entry.state)
        assertEquals(
            listOf(
                WorkerLifecycleSignalKind.LEASE_CAPACITY_EXCEEDED,
                WorkerLifecycleSignalKind.HEARTBEAT_LEASE_MISMATCH,
            ),
            result.signals,
        )
        assertEquals(HealthState.DEGRADED, graph.node(result.entry.descriptor.healthNodeId)?.state)
    }

    @Test
    fun `consistent heartbeat records healthy state and stale verification escalates`() = runTest {
        val workerId = WorkerId("worker-stale-heartbeat")
        val heartbeatAt = baseTime.plusSeconds(10)
        val leaseInspector = FakeLeaseInspector(baseTime)
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = WorkerLifecycleSupervisor(registry, leaseInspector, graph, signals) { baseTime }

        supervisor.attach(descriptor(workerId), FakeController(workerId))
        supervisor.start(workerId)
        val accepted = assertIs<WorkerHeartbeatResult.Accepted>(
            supervisor.observeHeartbeat(WorkerHeartbeat(workerId, heartbeatAt, reportedActiveWork = 0))
        )
        assertEquals(HealthState.HEALTHY, graph.node(accepted.entry.descriptor.healthNodeId)?.state)

        assertTrue(
            supervisor.verifyHeartbeat(
                workerId = workerId,
                at = heartbeatAt.plusSeconds(30),
                maxSilence = Duration.ofSeconds(30),
            )
        )
        assertFalse(
            supervisor.verifyHeartbeat(
                workerId = workerId,
                at = heartbeatAt.plusSeconds(31),
                maxSilence = Duration.ofSeconds(30),
            )
        )
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.HEARTBEAT_STALE })
    }

    @Test
    fun `out of order heartbeat is ignored without replacing last accepted heartbeat`() = runTest {
        val workerId = WorkerId("worker-heartbeat-order")
        val leaseInspector = FakeLeaseInspector(baseTime)
        val signals = RecordingSignalSink()
        val supervisor = WorkerLifecycleSupervisor(
            WorkerRegistry(),
            leaseInspector,
            HealthGraph(),
            signals,
        ) { baseTime }

        supervisor.attach(descriptor(workerId), FakeController(workerId))
        supervisor.start(workerId)
        supervisor.observeHeartbeat(WorkerHeartbeat(workerId, baseTime.plusSeconds(20), 0))
        val ignored = assertIs<WorkerHeartbeatResult.Ignored>(
            supervisor.observeHeartbeat(WorkerHeartbeat(workerId, baseTime.plusSeconds(19), 0))
        )

        assertEquals("out-of-order-heartbeat", ignored.reason)
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.HEARTBEAT_OUT_OF_ORDER })
        assertTrue(
            supervisor.verifyHeartbeat(
                workerId,
                baseTime.plusSeconds(50),
                Duration.ofSeconds(30),
            )
        )
    }

    @Test
    fun `same id replacement requires complete stop and resets implementation version`() = runTest {
        val workerId = WorkerId("worker-replace")
        val registry = WorkerRegistry()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            FakeLeaseInspector(baseTime),
            HealthGraph(),
        ) { baseTime }
        val first = descriptor(workerId, version = WorkerVersion(1, 0, 0), fingerprint = "impl-v1")
        val second = descriptor(workerId, version = WorkerVersion(2, 0, 0), fingerprint = "impl-v2")

        supervisor.attach(first, FakeController(workerId))
        supervisor.start(workerId)
        assertFailsWith<IllegalArgumentException> {
            supervisor.replaceStopped(second, FakeController(workerId))
        }

        assertIs<WorkerDrainResult.Drained>(supervisor.drain(workerId))
        val replaced = assertIs<WorkerRegistrationResult.Registered>(
            supervisor.replaceStopped(second, FakeController(workerId))
        )
        assertEquals(WorkerVersion(1, 0, 0), replaced.replacedStoppedVersion)
        assertEquals(WorkerVersion(2, 0, 0), registry.entry(workerId)?.descriptor?.version)
        assertEquals(WorkerRuntimeState.REGISTERED, registry.entry(workerId)?.state)
    }

    @Test
    fun `canary promotion requires drained incumbent and healthy runnable canary`() = runTest {
        val incumbentId = WorkerId("worker-primary")
        val canaryId = WorkerId("worker-canary")
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val signals = RecordingSignalSink()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            FakeLeaseInspector(baseTime),
            graph,
            signals,
        ) { baseTime }

        supervisor.attach(descriptor(incumbentId), FakeController(incumbentId))
        supervisor.start(incumbentId)
        supervisor.stageCanary(
            incumbentId = incumbentId,
            descriptor = descriptor(canaryId, version = WorkerVersion(2), fingerprint = "canary-v2"),
            controller = FakeController(canaryId),
        )
        supervisor.start(canaryId)

        val rejected = assertIs<WorkerCanaryPromotionResult.Rejected>(
            supervisor.promoteCanary(incumbentId, canaryId)
        )
        assertEquals("incumbent-not-stopped", rejected.reason)
        assertEquals(WorkerDeploymentRole.PRIMARY, supervisor.role(incumbentId))
        assertEquals(WorkerDeploymentRole.CANARY, supervisor.role(canaryId))

        supervisor.drain(incumbentId)
        val promoted = assertIs<WorkerCanaryPromotionResult.Promoted>(
            supervisor.promoteCanary(incumbentId, canaryId)
        )
        assertEquals(canaryId, promoted.canaryId)
        assertEquals(incumbentId, promoted.retiredIncumbentId)
        assertEquals(WorkerDeploymentRole.RETIRED, supervisor.role(incumbentId))
        assertEquals(WorkerDeploymentRole.PRIMARY, supervisor.role(canaryId))
    }

    @Test
    fun `start failure is contained and cancellation still propagates`() = runTest {
        val failedId = WorkerId("worker-start-failure")
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val supervisor = WorkerLifecycleSupervisor(
            registry,
            FakeLeaseInspector(baseTime),
            HealthGraph(),
            signals,
        ) { baseTime }
        supervisor.attach(
            descriptor(failedId),
            FakeController(failedId, startFailure = IllegalStateException("boom")),
        )

        val failed = assertIs<WorkerStartResult.Failed>(supervisor.start(failedId))
        assertEquals(WorkerRuntimeState.STOPPED, failed.entry.state)
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.START_FAILED })

        val cancelledId = WorkerId("worker-start-cancelled")
        supervisor.attach(
            descriptor(cancelledId),
            FakeController(cancelledId, startFailure = CancellationException("cancel")),
        )
        assertFailsWith<CancellationException> { supervisor.start(cancelledId) }
        assertEquals(WorkerRuntimeState.REGISTERED, registry.entry(cancelledId)?.state)
    }

    private fun descriptor(
        workerId: WorkerId,
        version: WorkerVersion = WorkerVersion(1),
        maxConcurrency: Int = 2,
        fingerprint: String = "${workerId.value}/v${version}",
    ) = WorkerDescriptor(
        workerId = workerId,
        version = version,
        capabilities = setOf(CapabilityId("cognition.process")),
        maxConcurrency = maxConcurrency,
        implementationFingerprint = fingerprint,
    )

    private class FakeController(
        override val workerId: WorkerId,
        private val startFailure: Exception? = null,
        private val stopAcceptingFailure: Exception? = null,
        private val stopFailure: Exception? = null,
    ) : WorkerRuntimeController {
        var startCalls: Int = 0
        var stopAcceptingCalls: Int = 0
        var stopCalls: Int = 0

        override suspend fun start() {
            startCalls += 1
            startFailure?.let { throw it }
        }

        override suspend fun stopAcceptingNewWork() {
            stopAcceptingCalls += 1
            stopAcceptingFailure?.let { throw it }
        }

        override suspend fun stop() {
            stopCalls += 1
            stopFailure?.let { throw it }
        }
    }

    private class FakeLeaseInspector(
        private val defaultTime: Instant,
    ) : WorkerLeaseInspector {
        val owned = mutableMapOf<WorkerId, List<TaskId>>()
        val unreadable = mutableMapOf<WorkerId, List<String>>()

        override suspend fun inspect(workerId: WorkerId, at: Instant): WorkerLeaseSnapshot =
            WorkerLeaseSnapshot(
                workerId = workerId,
                ownedTaskIds = owned[workerId].orEmpty().distinct().sortedBy { it.value },
                unreadableEntries = unreadable[workerId].orEmpty().distinct().sorted(),
                capturedAt = if (at == Instant.MIN) defaultTime else at,
            )
    }

    private class RecordingSignalSink : WorkerLifecycleSignalSink {
        val events = mutableListOf<WorkerLifecycleSignal>()

        override suspend fun emit(signal: WorkerLifecycleSignal) {
            events += signal
        }
    }
}
