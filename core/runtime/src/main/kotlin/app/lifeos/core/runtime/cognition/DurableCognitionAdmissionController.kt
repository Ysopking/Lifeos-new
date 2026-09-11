package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounds the durable cognition backlog at the TaskStore boundary.
 *
 * Admission is serialized so concurrent producers cannot all observe the same free slot. Existing
 * idempotent work is always allowed through to [DurableTaskEngine], even when the backlog is full,
 * so CREATED tasks can still be resumed and duplicate semantic work never creates a second task.
 * A task-ledger integrity failure fails closed rather than admitting unbounded new work.
 */
class DurableCognitionAdmissionController(
    private val tasks: TaskSnapshotRepository,
    private val taskEngine: DurableTaskEngine,
    private val maxActiveTasks: Int = DEFAULT_MAX_ACTIVE_TASKS,
) {
    private val mutex = Mutex()

    init {
        require(maxActiveTasks > 0) { "Durable cognition capacity must be positive" }
    }

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

        val active = report.tasks.count { task ->
            task.type.isCognitionTask() && !task.state.isTerminal()
        }
        if (active >= maxActiveTasks) return@withLock null

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
    }
}
