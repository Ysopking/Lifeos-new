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
        val leases = FakeLeaseInspector().apply {
            owned[workerId] = listOf(TaskId("task-b"), TaskId("task-a"))
        }
        val registry = WorkerRegistry()
        val supervisor = supervisor(registry, leases)

        supervisor.attach(descriptor(workerId), controller)
        assertIs<WorkerStartResult.Started>(supervisor.start(workerId))

        val waiting = assertIs<WorkerDrainResult.WaitingForLeases>(supervisor.drain(workerId))
        assertEquals(listOf("task-a", "task-b"), waiting.ownedTaskIds.map { it.value })
        assertEquals(WorkerRuntimeState.DRAINING, registry.entry(workerId)?.state)
        assertEquals(1, controller.stopAcceptingCalls)
        assertEquals(0, controller.stopCalls)

        leases.owned[workerId] = emptyList()
        assertIs<WorkerDrainResult.Drained>(supervisor.drain(workerId))
        assertEquals(WorkerRuntimeState.STOPPED, registry.entry(workerId)?.state)
        assertEquals(1, controller.stopCalls)
    }

    @Test
    fun `incomplete lease inspection blocks physical stop`() = runTest {
        val workerId = WorkerId("worker-corrupt-leases")
        val controller = FakeController(workerId)
        val leases = FakeLeaseInspector().apply { unreadable[workerId] = listOf("vault-b", "vault-a") }
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = supervisor(registry, leases, graph, signals)

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
    fun `heartbeat reconciles load from durable leases and invariant mismatch is unhealthy`() = runTest {
        val workerId = WorkerId("worker-heartbeat")
        val leases = FakeLeaseInspector().apply {
            owned[workerId] = listOf(TaskId("lease-1"), TaskId("lease-2"))
        }
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = supervisor(registry, leases, graph, signals)

        supervisor.attach(descriptor(workerId, maxConcurrency = 1), FakeController(workerId))
        supervisor.start(workerId)
        val result = assertIs<WorkerHeartbeatResult.Inconsistent>(
            supervisor.observeHeartbeat(WorkerHeartbeat(workerId, baseTime.plusSeconds(5), 0))
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
        assertEquals(HealthState.UNHEALTHY, graph.node(result.entry.descriptor.healthNodeId)?.state)
    }

    @Test
    fun `consistent heartbeat is healthy and stale heartbeat escalates`() = runTest {
        val workerId = WorkerId("worker-stale-heartbeat")
        val heartbeatAt = baseTime.plusSeconds(10)
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val supervisor = supervisor(registry, FakeLeaseInspector(), graph, signals)

        supervisor.attach(descriptor(workerId), FakeController(workerId))
        supervisor.start(workerId)
        val accepted = assertIs<WorkerHeartbeatResult.Accepted>(
            supervisor.observeHeartbeat(WorkerHeartbeat(workerId, heartbeatAt, 0))
        )
        assertEquals(HealthState.HEALTHY, graph.node(accepted.entry.descriptor.healthNodeId)?.state)
        assertTrue(supervisor.verifyHeartbeat(workerId, heartbeatAt.plusSeconds(30), Duration.ofSeconds(30)))
        assertFalse(supervisor.verifyHeartbeat(workerId, heartbeatAt.plusSeconds(31), Duration.ofSeconds(30)))
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.HEARTBEAT_STALE })
    }

    @Test
    fun `out of order heartbeat is ignored`() = runTest {
        val workerId = WorkerId("worker-heartbeat-order")
        val signals = RecordingSignalSink()
        val supervisor = supervisor(WorkerRegistry(), FakeLeaseInspector(), HealthGraph(), signals)

        supervisor.attach(descriptor(workerId), FakeController(workerId))
        supervisor.start(workerId)
        supervisor.observeHeartbeat(WorkerHeartbeat(workerId, baseTime.plusSeconds(20), 0))
        val ignored = assertIs<WorkerHeartbeatResult.Ignored>(
            supervisor.observeHeartbeat(WorkerHeartbeat(workerId, baseTime.plusSeconds(19), 0))
        )

        assertEquals("out-of-order-heartbeat", ignored.reason)
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.HEARTBEAT_OUT_OF_ORDER })
    }

    @Test
    fun `same id replacement requires complete stop`() = runTest {
        val workerId = WorkerId("worker-replace")
        val registry = WorkerRegistry()
        val supervisor = supervisor(registry, FakeLeaseInspector())
        val first = descriptor(workerId, WorkerVersion(1), fingerprint = "impl-v1")
        val second = descriptor(workerId, WorkerVersion(2), fingerprint = "impl-v2")

        supervisor.attach(first, FakeController(workerId))
        supervisor.start(workerId)
        assertFailsWith<IllegalArgumentException> {
            supervisor.replaceStopped(second, FakeController(workerId))
        }

        assertIs<WorkerDrainResult.Drained>(supervisor.drain(workerId))
        val replaced = assertIs<WorkerRegistrationResult.Registered>(
            supervisor.replaceStopped(second, FakeController(workerId))
        )
        assertEquals(WorkerVersion(1), replaced.replacedStoppedVersion)
        assertEquals(WorkerVersion(2), registry.entry(workerId)?.descriptor?.version)
        assertEquals(WorkerRuntimeState.REGISTERED, registry.entry(workerId)?.state)
    }

    @Test
    fun `canary promotion requires drained incumbent and healthy runnable canary`() = runTest {
        val incumbentId = WorkerId("worker-primary")
        val canaryId = WorkerId("worker-canary")
        val registry = WorkerRegistry()
        val graph = HealthGraph()
        val signals = RecordingSignalSink()
        val supervisor = supervisor(registry, FakeLeaseInspector(), graph, signals)

        supervisor.attach(descriptor(incumbentId), FakeController(incumbentId))
        supervisor.start(incumbentId)
        supervisor.stageCanary(
            incumbentId,
            descriptor(canaryId, WorkerVersion(2), fingerprint = "canary-v2"),
            FakeController(canaryId),
        )
        supervisor.start(canaryId)

        val rejected = assertIs<WorkerCanaryPromotionResult.Rejected>(
            supervisor.promoteCanary(incumbentId, canaryId)
        )
        assertEquals("incumbent-not-stopped", rejected.reason)

        supervisor.drain(incumbentId)
        assertIs<WorkerCanaryPromotionResult.Promoted>(supervisor.promoteCanary(incumbentId, canaryId))
        assertEquals(WorkerDeploymentRole.RETIRED, supervisor.role(incumbentId))
        assertEquals(WorkerDeploymentRole.PRIMARY, supervisor.role(canaryId))
    }

    @Test
    fun `start failure is contained but cancellation propagates`() = runTest {
        val failedId = WorkerId("worker-start-failure")
        val signals = RecordingSignalSink()
        val registry = WorkerRegistry()
        val supervisor = supervisor(registry, FakeLeaseInspector(), HealthGraph(), signals)
        supervisor.attach(descriptor(failedId), FakeController(failedId, IllegalStateException("boom")))

        val failed = assertIs<WorkerStartResult.Failed>(supervisor.start(failedId))
        assertEquals(WorkerRuntimeState.STOPPED, failed.entry.state)
        assertTrue(signals.events.any { it.kind == WorkerLifecycleSignalKind.START_FAILED })

        val cancelledId = WorkerId("worker-start-cancelled")
        supervisor.attach(descriptor(cancelledId), FakeController(cancelledId, CancellationException("cancel")))
        assertFailsWith<CancellationException> { supervisor.start(cancelledId) }
        assertEquals(WorkerRuntimeState.REGISTERED, registry.entry(cancelledId)?.state)
    }

    private fun supervisor(
        registry: WorkerRegistry,
        leases: WorkerLeaseInspector,
        graph: HealthGraph = HealthGraph(),
        signals: WorkerLifecycleSignalSink = NoOpWorkerLifecycleSignalSink,
    ) = WorkerLifecycleSupervisor(registry, leases, graph, signals) { baseTime }

    private fun descriptor(
        workerId: WorkerId,
        version: WorkerVersion = WorkerVersion(1),
        maxConcurrency: Int = 2,
        fingerprint: String = "${workerId.value}/v$version",
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
    ) : WorkerRuntimeController {
        var stopAcceptingCalls = 0
        var stopCalls = 0

        override suspend fun start() {
            startFailure?.let { throw it }
        }

        override suspend fun stopAcceptingNewWork() {
            stopAcceptingCalls += 1
        }

        override suspend fun stop() {
            stopCalls += 1
        }
    }

    private class FakeLeaseInspector : WorkerLeaseInspector {
        val owned = mutableMapOf<WorkerId, List<TaskId>>()
        val unreadable = mutableMapOf<WorkerId, List<String>>()

        override suspend fun inspect(workerId: WorkerId, at: Instant) = WorkerLeaseSnapshot(
            workerId = workerId,
            ownedTaskIds = owned[workerId].orEmpty().distinct().sortedBy { it.value },
            unreadableEntries = unreadable[workerId].orEmpty().distinct().sorted(),
            capturedAt = at,
        )
    }

    private class RecordingSignalSink : WorkerLifecycleSignalSink {
        val events = mutableListOf<WorkerLifecycleSignal>()
        override suspend fun emit(signal: WorkerLifecycleSignal) {
            events += signal
        }
    }
}
