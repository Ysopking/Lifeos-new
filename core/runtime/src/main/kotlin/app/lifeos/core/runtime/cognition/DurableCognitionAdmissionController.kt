package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import java.time.Instant
import kotlinx.coroutines.sync.withLock

/**
 * Bounds the durable cognition backlog at the TaskStore boundary.
 *
 * Admission is serialized across every controller attached to the same [DurableTaskEngine], so
 * independent live-delta and feedback producers cannot all observe the same free slot. Existing
 * idempotent work is always allowed through even when the backlog is full. New cognition work may
 * additionally be rejected by the shared V16 World Formula broker when current device capacity is
 * better spent on higher-value work. A task-ledger integrity failure fails closed.
 */
class DurableCognitionAdmissionController(
    private val tasks: TaskSnapshotRepository,
    private val taskEngine: DurableTaskEngine,
    private val maxActiveTasks: Int = DEFAULT_MAX_ACTIVE_TASKS,
    private val foregroundReserve: Int = DEFAULT_FOREGROUND_RESERVE,
    private val sharedBudgets: SharedResourceBudgetGate? = SharedResourceBudgetRuntimeRegistry.current(),
) {
    private val mutex = taskEngine.cognitionAdmissionMutex

    init {
        require(maxActiveTasks > 0) { "Durable cognition capacity must be positive" }
        require(foregroundReserve >= 0) { "Foreground cognition reserve must not be negative" }
    }

    private val effectiveForegroundReserve: Int
        get() = foregroundReserve.coerceAtMost((maxActiveTasks - 1).coerceAtLeast(0))

    suspend fun submit(draft: TaskDraft): LifeTask? = mutex.withLock {
        require(draft.type.isCognitionTask()) { "Admission controller accepts cognition tasks only" }

        val report = tasks.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot admit cognition with unreadable durable task entries"
        }

        val existing = report.tasks.firstOrNull { it.idempotencyKey == draft.idempotencyKey }
        if (existing != null) {
            check(existing.type == draft.type) {
                "Idempotency key collision across task types: ${draft.idempotencyKey}"
            }
            return@withLock taskEngine.submit(draft)
        }

        var active = report.tasks.count { task ->
            task.type.isCognitionTask() && !task.state.isTerminal()
        }
        val foreground = draft.priority == TaskPriority.INTERACTIVE || draft.priority == TaskPriority.CRITICAL
        val backgroundCeiling = maxActiveTasks - effectiveForegroundReserve

        if (!foreground && active >= backgroundCeiling) {
            return@withLock null
        }

        if (foreground && active >= maxActiveTasks) {
            val victim = report.tasks.asSequence()
                .filter { task ->
                    task.type.isCognitionTask() &&
                        task.priority == TaskPriority.BACKGROUND &&
                        task.state in setOf(TaskState.QUEUED, TaskState.RETRY_WAIT)
                }
                .sortedWith(
                    compareBy<LifeTask> { it.priority.weight }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id.value }
                )
                .firstOrNull()
                ?: return@withLock null

            val cancelled = tasks.transition(
                id = victim.id,
                expected = victim.state,
                next = TaskState.CANCELLED,
                at = Instant.now(),
            ) ?: return@withLock null
            check(cancelled.state == TaskState.CANCELLED) {
                "Foreground preemption did not cancel background task"
            }
            active -= 1
        }

        check(active < maxActiveTasks) {
            "Foreground cognition admission exceeded hard durable capacity"
        }

        sharedBudgets?.let { broker ->
            val priority = (draft.priority.weight.toDouble() / 100.0).coerceIn(0.0, 1.0)
            val demand = ResourceBudgetDemand(
                domain = ResourceBudgetDomain.COGNITION,
                requested = COGNITION_ADMISSION_REQUEST,
                goalRelevance = priority,
                priority = priority,
                expectedUtility = if (draft.priority.weight >= 75) 0.85 else 0.60,
                confidence = 1.0,
            )
            when (val decision = broker.allocate(COGNITION_HARD_QUOTA, listOf(demand))) {
                is SharedResourceBudgetDecision.Blocked -> return@withLock null
                is SharedResourceBudgetDecision.Ready -> {
                    val allocation = decision.allocation.allocation(ResourceBudgetDomain.COGNITION)
                        ?: return@withLock null
                    if (!COGNITION_ADMISSION_REQUEST.isWithin(allocation.allocated)) {
                        return@withLock null
                    }
                }
            }
        }

        taskEngine.submit(draft)
    }

    suspend fun availableCapacity(): Int = mutex.withLock {
        val report = tasks.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot inspect cognition capacity with unreadable durable task entries"
        }
        val active = report.tasks.count { task ->
            task.type.isCognitionTask() && !task.state.isTerminal()
        }
        (maxActiveTasks - active).coerceAtLeast(0)
    }

    private fun TaskType.isCognitionTask(): Boolean =
        this == TaskType.PROCESS_PHOTON || this == TaskType.REPROCESS_PHOTON

    private fun TaskState.isTerminal(): Boolean = when (this) {
        TaskState.COMPLETED,
        TaskState.SUPERSEDED,
        TaskState.FAILED,
        TaskState.CANCELLED -> true

        else -> false
    }

    private companion object {
        const val DEFAULT_MAX_ACTIVE_TASKS = 100
        const val DEFAULT_FOREGROUND_RESERVE = 8
        val COGNITION_HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 5_000,
            workUnits = 32,
            memoryBytes = 128L * 1024L * 1024L,
            ioBytes = 16L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 8,
        )
        val COGNITION_ADMISSION_REQUEST = ResourceBudgetUsage(
            elapsedMillis = 500,
            workUnits = 1,
            memoryBytes = 8L * 1024L * 1024L,
            ioBytes = 512L * 1024L,
            networkBytes = 0,
            candidates = 1,
        )
    }
}
