package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WorkerDeploymentRole {
    PRIMARY,
    CANARY,
    RETIRED,
}

/** Controller port. Implementations own process/coroutine mechanics; the supervisor owns policy. */
interface WorkerRuntimeController {
    val workerId: WorkerId

    suspend fun start()

    /** Stops admission of new work without cancelling already-owned durable tasks. */
    suspend fun stopAcceptingNewWork()

    /** Called only after the lease inspector proves that no durable task is still owned. */
    suspend fun stop()
}

data class WorkerLeaseSnapshot(
    val workerId: WorkerId,
    val ownedTaskIds: List<TaskId>,
    val unreadableEntries: List<String>,
    val capturedAt: Instant,
) {
    init {
        require(ownedTaskIds == ownedTaskIds.distinct().sortedBy { it.value }) {
            "Owned worker task ids must be unique and deterministically ordered"
        }
        require(unreadableEntries == unreadableEntries.distinct().sorted()) {
            "Unreadable lease entries must be unique and deterministically ordered"
        }
    }

    val complete: Boolean
        get() = unreadableEntries.isEmpty()
}

interface WorkerLeaseInspector {
    suspend fun inspect(workerId: WorkerId, at: Instant): WorkerLeaseSnapshot
}

/** Read-only bridge from durable task truth into lifecycle decisions. It never mutates leases. */
class TaskSnapshotWorkerLeaseInspector(
    private val tasks: TaskSnapshotRepository,
) : WorkerLeaseInspector {
    override suspend fun inspect(workerId: WorkerId, at: Instant): WorkerLeaseSnapshot {
        val report = tasks.loadReport()
        return WorkerLeaseSnapshot(
            workerId = workerId,
            ownedTaskIds = report.tasks
                .asSequence()
                .filter { it.claimedBy == workerId }
                .map { it.id }
                .distinct()
                .sortedBy { it.value }
                .toList(),
            unreadableEntries = report.unreadableEntries.distinct().sorted(),
            capturedAt = at,
        )
    }
}

data class WorkerHeartbeat(
    val workerId: WorkerId,
    val observedAt: Instant,
    val reportedActiveWork: Int,
) {
    init {
        require(reportedActiveWork >= 0) { "Heartbeat active work must not be negative" }
    }
}

enum class WorkerLifecycleSignalKind {
    START_FAILED,
    DRAIN_START_FAILED,
    LEASE_INSPECTION_INCOMPLETE,
    LEASE_CAPACITY_EXCEEDED,
    HEARTBEAT_LEASE_MISMATCH,
    HEARTBEAT_OUT_OF_ORDER,
    HEARTBEAT_STALE,
    STOP_FAILED,
    CANARY_REJECTED,
}

data class WorkerLifecycleSignal(
    val kind: WorkerLifecycleSignalKind,
    val workerId: WorkerId,
    val observedAt: Instant,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Worker lifecycle signal message must not be blank" }
    }
}

fun interface WorkerLifecycleSignalSink {
    suspend fun emit(signal: WorkerLifecycleSignal)
}

object NoOpWorkerLifecycleSignalSink : WorkerLifecycleSignalSink {
    override suspend fun emit(signal: WorkerLifecycleSignal) = Unit
}

sealed interface WorkerStartResult {
    data class Started(val entry: WorkerRegistryEntry) : WorkerStartResult
    data class AlreadyRunning(val entry: WorkerRegistryEntry) : WorkerStartResult
    data class Rejected(val entry: WorkerRegistryEntry, val reason: String) : WorkerStartResult
    data class Failed(val entry: WorkerRegistryEntry, val message: String) : WorkerStartResult
}

sealed interface WorkerDrainResult {
    data class Drained(val entry: WorkerRegistryEntry) : WorkerDrainResult

    data class WaitingForLeases(
        val entry: WorkerRegistryEntry,
        val ownedTaskIds: List<TaskId>,
    ) : WorkerDrainResult

    data class Blocked(
        val entry: WorkerRegistryEntry,
        val unreadableEntries: List<String>,
    ) : WorkerDrainResult

    data class Failed(val entry: WorkerRegistryEntry, val message: String) : WorkerDrainResult
}

sealed interface WorkerHeartbeatResult {
    data class Accepted(
        val entry: WorkerRegistryEntry,
        val actualActiveWork: Int,
    ) : WorkerHeartbeatResult

    data class Inconsistent(
        val entry: WorkerRegistryEntry,
        val actualActiveWork: Int,
        val signals: List<WorkerLifecycleSignalKind>,
    ) : WorkerHeartbeatResult

    data class Blocked(
        val entry: WorkerRegistryEntry,
        val unreadableEntries: List<String>,
    ) : WorkerHeartbeatResult

    data class Ignored(
        val entry: WorkerRegistryEntry,
        val reason: String,
    ) : WorkerHeartbeatResult
}

sealed interface WorkerCanaryPromotionResult {
    data class Promoted(
        val canaryId: WorkerId,
        val retiredIncumbentId: WorkerId,
    ) : WorkerCanaryPromotionResult

    data class Rejected(val reason: String) : WorkerCanaryPromotionResult
}

/**
 * Single policy boundary for worker start/stop/drain, heartbeat/lease reconciliation and
 * replacement/canary transitions. No operation clears, transfers or fabricates durable leases.
 */
class WorkerLifecycleSupervisor(
    private val registry: WorkerRegistry,
    private val leases: WorkerLeaseInspector,
    private val healthGraph: HealthGraph,
    private val signals: WorkerLifecycleSignalSink = NoOpWorkerLifecycleSignalSink,
    private val now: () -> Instant = Instant::now,
) {
    private class Slot(
        var controller: WorkerRuntimeController,
        var role: WorkerDeploymentRole,
        var lastHeartbeatAt: Instant? = null,
        val mutex: Mutex = Mutex(),
    )

    private val slotsLock = Any()
    private val slots = linkedMapOf<WorkerId, Slot>()

    suspend fun attach(
        descriptor: WorkerDescriptor,
        controller: WorkerRuntimeController,
        role: WorkerDeploymentRole = WorkerDeploymentRole.PRIMARY,
    ): WorkerRegistrationResult {
        require(controller.workerId == descriptor.workerId) {
            "Worker controller id must match descriptor id"
        }
        synchronized(slotsLock) {
            val existing = slots[descriptor.workerId]
            require(existing == null || existing.controller === controller) {
                "Worker ${descriptor.workerId.value} already has a different runtime controller"
            }
        }

        healthGraph.register(descriptor.healthNodeId, HealthScope.WORKER)
        val registration = registry.register(descriptor, WorkerRuntimeState.REGISTERED)
        if (registration is WorkerRegistrationResult.Rejected) return registration

        synchronized(slotsLock) {
            slots.putIfAbsent(descriptor.workerId, Slot(controller = controller, role = role))
        }
        return registration
    }

    fun role(workerId: WorkerId): WorkerDeploymentRole? = synchronized(slotsLock) {
        slots[workerId]?.role
    }

    suspend fun start(workerId: WorkerId): WorkerStartResult = slot(workerId).mutex.withLock {
        val managed = slot(workerId)
        val current = requireNotNull(registry.entry(workerId)) {
            "Worker ${workerId.value} is attached without registry entry"
        }
        if (managed.role == WorkerDeploymentRole.RETIRED) {
            return@withLock WorkerStartResult.Rejected(current, "retired-worker")
        }
        if (current.state == WorkerRuntimeState.READY || current.state == WorkerRuntimeState.BUSY) {
            return@withLock WorkerStartResult.AlreadyRunning(current)
        }
        if (current.state == WorkerRuntimeState.DRAINING) {
            return@withLock WorkerStartResult.Rejected(current, "worker-is-draining")
        }

        try {
            managed.controller.start()
            registry.updateLoad(workerId, activeWork = 0)
            val started = registry.updateState(workerId, WorkerRuntimeState.READY)
            healthGraph.recordHealthy(
                id = started.descriptor.healthNodeId,
                source = "worker-lifecycle",
                message = "worker-started:${started.descriptor.version}",
                observedAt = now(),
            )
            WorkerStartResult.Started(started)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failed = registry.updateState(workerId, WorkerRuntimeState.STOPPED)
            recordFailureAndSignal(
                entry = failed,
                kind = WorkerLifecycleSignalKind.START_FAILED,
                message = error.message ?: error::class.simpleName ?: "worker-start-failed",
                category = RuntimeFailureCategory.UNKNOWN,
            )
            WorkerStartResult.Failed(failed, error.message ?: "worker-start-failed")
        }
    }

    /**
     * Begins or advances a safe drain. If leases remain, the worker stays DRAINING and must be
     * checked again later. There is intentionally no force-stop path through this boundary.
     */
    suspend fun drain(workerId: WorkerId): WorkerDrainResult = slot(workerId).mutex.withLock {
        val managed = slot(workerId)
        var current = requireNotNull(registry.entry(workerId)) {
            "Worker ${workerId.value} is attached without registry entry"
        }
        if (current.state == WorkerRuntimeState.STOPPED) {
            return@withLock WorkerDrainResult.Drained(current)
        }
        if (current.state == WorkerRuntimeState.REGISTERED) {
            current = registry.updateLoad(workerId, activeWork = 0)
            current = registry.updateState(workerId, WorkerRuntimeState.STOPPED)
            return@withLock WorkerDrainResult.Drained(current)
        }

        if (current.state != WorkerRuntimeState.DRAINING) {
            try {
                managed.controller.stopAcceptingNewWork()
                current = registry.updateState(workerId, WorkerRuntimeState.DRAINING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailureAndSignal(
                    entry = current,
                    kind = WorkerLifecycleSignalKind.DRAIN_START_FAILED,
                    message = error.message ?: error::class.simpleName ?: "worker-drain-start-failed",
                    category = RuntimeFailureCategory.UNKNOWN,
                )
                return@withLock WorkerDrainResult.Failed(
                    current,
                    error.message ?: "worker-drain-start-failed",
                )
            }
        }

        val leaseSnapshot = leases.inspect(workerId, now())
        if (!leaseSnapshot.complete) {
            recordFailureAndSignal(
                entry = current,
                kind = WorkerLifecycleSignalKind.LEASE_INSPECTION_INCOMPLETE,
                message = "unreadable-lease-entries:${leaseSnapshot.unreadableEntries.size}",
                category = RuntimeFailureCategory.STORAGE,
            )
            return@withLock WorkerDrainResult.Blocked(
                entry = current,
                unreadableEntries = leaseSnapshot.unreadableEntries,
            )
        }

        current = registry.updateLoad(workerId, leaseSnapshot.ownedTaskIds.size)
        if (leaseSnapshot.ownedTaskIds.isNotEmpty()) {
            return@withLock WorkerDrainResult.WaitingForLeases(
                entry = current,
                ownedTaskIds = leaseSnapshot.ownedTaskIds,
            )
        }

        try {
            managed.controller.stop()
            current = registry.updateLoad(workerId, activeWork = 0)
            current = registry.updateState(workerId, WorkerRuntimeState.STOPPED)
            healthGraph.recordHealthy(
                id = current.descriptor.healthNodeId,
                source = "worker-lifecycle",
                message = "worker-drained-and-stopped",
                observedAt = now(),
            )
            WorkerDrainResult.Drained(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailureAndSignal(
                entry = current,
                kind = WorkerLifecycleSignalKind.STOP_FAILED,
                message = error.message ?: error::class.simpleName ?: "worker-stop-failed",
                category = RuntimeFailureCategory.UNKNOWN,
            )
            WorkerDrainResult.Failed(current, error.message ?: "worker-stop-failed")
        }
    }

    suspend fun stop(workerId: WorkerId): WorkerDrainResult = drain(workerId)

    suspend fun observeHeartbeat(heartbeat: WorkerHeartbeat): WorkerHeartbeatResult =
        slot(heartbeat.workerId).mutex.withLock {
            val managed = slot(heartbeat.workerId)
            var current = requireNotNull(registry.entry(heartbeat.workerId)) {
                "Worker ${heartbeat.workerId.value} is attached without registry entry"
            }
            if (current.state == WorkerRuntimeState.REGISTERED || current.state == WorkerRuntimeState.STOPPED) {
                return@withLock WorkerHeartbeatResult.Ignored(current, "worker-not-running")
            }

            val previousHeartbeat = managed.lastHeartbeatAt
            if (previousHeartbeat != null && heartbeat.observedAt.isBefore(previousHeartbeat)) {
                signals.emit(
                    WorkerLifecycleSignal(
                        kind = WorkerLifecycleSignalKind.HEARTBEAT_OUT_OF_ORDER,
                        workerId = heartbeat.workerId,
                        observedAt = heartbeat.observedAt,
                        message = "heartbeat-before:$previousHeartbeat",
                    )
                )
                return@withLock WorkerHeartbeatResult.Ignored(current, "out-of-order-heartbeat")
            }

            val leaseSnapshot = leases.inspect(heartbeat.workerId, heartbeat.observedAt)
            if (!leaseSnapshot.complete) {
                recordFailureAndSignal(
                    entry = current,
                    kind = WorkerLifecycleSignalKind.LEASE_INSPECTION_INCOMPLETE,
                    message = "unreadable-lease-entries:${leaseSnapshot.unreadableEntries.size}",
                    category = RuntimeFailureCategory.STORAGE,
                    observedAt = heartbeat.observedAt,
                )
                return@withLock WorkerHeartbeatResult.Blocked(
                    entry = current,
                    unreadableEntries = leaseSnapshot.unreadableEntries,
                )
            }

            val actualActiveWork = leaseSnapshot.ownedTaskIds.size
            current = registry.updateLoad(heartbeat.workerId, actualActiveWork)
            if (current.state != WorkerRuntimeState.DRAINING) {
                current = registry.updateState(
                    heartbeat.workerId,
                    if (actualActiveWork == 0) WorkerRuntimeState.READY else WorkerRuntimeState.BUSY,
                )
            }
            managed.lastHeartbeatAt = heartbeat.observedAt

            val inconsistencies = buildList {
                if (actualActiveWork > current.descriptor.maxConcurrency) {
                    add(WorkerLifecycleSignalKind.LEASE_CAPACITY_EXCEEDED)
                }
                if (heartbeat.reportedActiveWork != actualActiveWork) {
                    add(WorkerLifecycleSignalKind.HEARTBEAT_LEASE_MISMATCH)
                }
            }
            if (inconsistencies.isNotEmpty()) {
                val message = "reported:${heartbeat.reportedActiveWork},durable:$actualActiveWork"
                healthGraph.recordFailure(
                    id = current.descriptor.healthNodeId,
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "worker-lifecycle-heartbeat",
                        message = message,
                        recoverable = true,
                    ),
                    observedAt = heartbeat.observedAt,
                )
                inconsistencies.forEach { kind ->
                    signals.emit(
                        WorkerLifecycleSignal(
                            kind = kind,
                            workerId = heartbeat.workerId,
                            observedAt = heartbeat.observedAt,
                            message = message,
                        )
                    )
                }
                return@withLock WorkerHeartbeatResult.Inconsistent(
                    entry = current,
                    actualActiveWork = actualActiveWork,
                    signals = inconsistencies,
                )
            }

            healthGraph.recordHealthy(
                id = current.descriptor.healthNodeId,
                source = "worker-lifecycle-heartbeat",
                message = "heartbeat-lease-consistent:$actualActiveWork",
                observedAt = heartbeat.observedAt,
            )
            WorkerHeartbeatResult.Accepted(current, actualActiveWork)
        }

    suspend fun verifyHeartbeat(
        workerId: WorkerId,
        at: Instant = now(),
        maxSilence: Duration,
    ): Boolean = slot(workerId).mutex.withLock {
        require(!maxSilence.isZero && !maxSilence.isNegative) {
            "Heartbeat silence threshold must be positive"
        }
        val managed = slot(workerId)
        val current = requireNotNull(registry.entry(workerId)) {
            "Worker ${workerId.value} is attached without registry entry"
        }
        if (current.state == WorkerRuntimeState.REGISTERED || current.state == WorkerRuntimeState.STOPPED) {
            return@withLock true
        }
        val last = managed.lastHeartbeatAt
        val stale = last == null || at.isAfter(last.plus(maxSilence))
        if (!stale) return@withLock true

        recordFailureAndSignal(
            entry = current,
            kind = WorkerLifecycleSignalKind.HEARTBEAT_STALE,
            message = if (last == null) "heartbeat-never-observed" else "last-heartbeat:$last",
            category = RuntimeFailureCategory.TIMEOUT,
            observedAt = at,
        )
        false
    }

    /** Same-id implementation replacement is legal only after the incumbent is fully STOPPED. */
    suspend fun replaceStopped(
        descriptor: WorkerDescriptor,
        controller: WorkerRuntimeController,
    ): WorkerRegistrationResult = slot(descriptor.workerId).mutex.withLock {
        require(controller.workerId == descriptor.workerId) {
            "Replacement controller id must match descriptor id"
        }
        val managed = slot(descriptor.workerId)
        val current = requireNotNull(registry.entry(descriptor.workerId)) {
            "Replacement worker is not registered"
        }
        require(current.state == WorkerRuntimeState.STOPPED) {
            "Worker must be fully stopped before implementation replacement"
        }

        val registration = registry.register(descriptor, WorkerRuntimeState.REGISTERED)
        if (registration is WorkerRegistrationResult.Registered) {
            managed.controller = controller
            managed.lastHeartbeatAt = null
        }
        registration
    }

    /** Stages a distinct worker identity as a canary without changing the incumbent's role. */
    suspend fun stageCanary(
        incumbentId: WorkerId,
        descriptor: WorkerDescriptor,
        controller: WorkerRuntimeController,
    ): WorkerRegistrationResult {
        require(incumbentId != descriptor.workerId) { "Canary must use a distinct worker id" }
        val incumbent = requireNotNull(registry.entry(incumbentId)) { "Incumbent worker is not registered" }
        require(role(incumbentId) == WorkerDeploymentRole.PRIMARY) {
            "Canary incumbent must be the primary worker"
        }
        require(descriptor.capabilities.containsAll(incumbent.descriptor.capabilities)) {
            "Canary must cover every incumbent capability"
        }
        return attach(descriptor, controller, WorkerDeploymentRole.CANARY)
    }

    /**
     * Promotion never stops the incumbent implicitly: callers must drain it first and the canary
     * must already be runnable and HEALTHY according to the shared HealthGraph.
     */
    suspend fun promoteCanary(
        incumbentId: WorkerId,
        canaryId: WorkerId,
    ): WorkerCanaryPromotionResult {
        require(incumbentId != canaryId) { "Canary and incumbent ids must differ" }
        val incumbentSlot = slot(incumbentId)
        val canarySlot = slot(canaryId)
        val ordered = listOf(incumbentId to incumbentSlot, canaryId to canarySlot)
            .sortedBy { it.first.value }
        return ordered[0].second.mutex.withLock {
            ordered[1].second.mutex.withLock {
                val incumbent = requireNotNull(registry.entry(incumbentId))
                val canary = requireNotNull(registry.entry(canaryId))
                val rejection = when {
                    incumbentSlot.role != WorkerDeploymentRole.PRIMARY -> "incumbent-not-primary"
                    canarySlot.role != WorkerDeploymentRole.CANARY -> "candidate-not-canary"
                    incumbent.state != WorkerRuntimeState.STOPPED -> "incumbent-not-stopped"
                    canary.state != WorkerRuntimeState.READY && canary.state != WorkerRuntimeState.BUSY ->
                        "canary-not-runnable"
                    healthGraph.node(canary.descriptor.healthNodeId)?.state != HealthState.HEALTHY ->
                        "canary-not-healthy"
                    else -> null
                }
                if (rejection != null) {
                    signals.emit(
                        WorkerLifecycleSignal(
                            kind = WorkerLifecycleSignalKind.CANARY_REJECTED,
                            workerId = canaryId,
                            observedAt = now(),
                            message = rejection,
                        )
                    )
                    return@withLock WorkerCanaryPromotionResult.Rejected(rejection)
                }

                synchronized(slotsLock) {
                    incumbentSlot.role = WorkerDeploymentRole.RETIRED
                    canarySlot.role = WorkerDeploymentRole.PRIMARY
                }
                WorkerCanaryPromotionResult.Promoted(
                    canaryId = canaryId,
                    retiredIncumbentId = incumbentId,
                )
            }
        }
    }

    private fun slot(workerId: WorkerId): Slot = synchronized(slotsLock) {
        requireNotNull(slots[workerId]) { "Worker ${workerId.value} has no lifecycle controller" }
    }

    private suspend fun recordFailureAndSignal(
        entry: WorkerRegistryEntry,
        kind: WorkerLifecycleSignalKind,
        message: String,
        category: RuntimeFailureCategory,
        observedAt: Instant = now(),
    ) {
        healthGraph.recordFailure(
            id = entry.descriptor.healthNodeId,
            failure = RuntimeFailure(
                category = category,
                source = "worker-lifecycle:${kind.name.lowercase()}",
                message = message,
                recoverable = true,
            ),
            observedAt = observedAt,
        )
        signals.emit(
            WorkerLifecycleSignal(
                kind = kind,
                workerId = entry.descriptor.workerId,
                observedAt = observedAt,
                message = message,
            )
        )
    }
}
