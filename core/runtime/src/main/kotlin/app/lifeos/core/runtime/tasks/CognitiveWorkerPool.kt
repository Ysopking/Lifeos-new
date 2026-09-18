package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.worker.WorkerId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

enum class CognitiveWorkerLane {
    INTERACTIVE,
    ACTIVE,
    BACKGROUND,
    MAINTENANCE,
}

data class CognitiveWorkerSlot(
    val lane: CognitiveWorkerLane,
    val workerId: WorkerId,
    val dispatcher: ClaimedTaskDispatcher,
)

/**
 * Immutable pool over the existing TaskStore authority. Selection happens before claim so worker
 * ownership and lease invariants remain exact.
 */
class CognitiveWorkerPool(
    slots: List<CognitiveWorkerSlot>,
) {
    val slots: List<CognitiveWorkerSlot> = slots.toList()

    private val byLane = CognitiveWorkerLane.values().associateWith { lane ->
        this.slots.filter { it.lane == lane }
    }
    private val cursors = CognitiveWorkerLane.values().associateWith { AtomicInteger(0) }

    init {
        require(this.slots.isNotEmpty()) { "Cognitive worker pool requires at least one slot" }
        require(this.slots.map { it.workerId }.distinct().size == this.slots.size) {
            "Cognitive worker ids must be unique"
        }
        require(byLane.getValue(CognitiveWorkerLane.INTERACTIVE).isNotEmpty()) {
            "Cognitive worker pool requires a reserved INTERACTIVE slot"
        }
    }

    fun select(priority: TaskPriority): CognitiveWorkerSlot {
        val preferred = when (priority) {
            TaskPriority.CRITICAL,
            TaskPriority.INTERACTIVE -> listOf(
                CognitiveWorkerLane.INTERACTIVE,
                CognitiveWorkerLane.ACTIVE,
            )
            TaskPriority.HIGH,
            TaskPriority.NORMAL -> listOf(
                CognitiveWorkerLane.ACTIVE,
                CognitiveWorkerLane.INTERACTIVE,
            )
            TaskPriority.BACKGROUND -> listOf(
                CognitiveWorkerLane.BACKGROUND,
                CognitiveWorkerLane.ACTIVE,
                CognitiveWorkerLane.INTERACTIVE,
            )
        }
        val lane = preferred.first { byLane.getValue(it).isNotEmpty() }
        val candidates = byLane.getValue(lane)
        val index = Math.floorMod(cursors.getValue(lane).getAndIncrement(), candidates.size)
        return candidates[index]
    }

    fun count(lane: CognitiveWorkerLane): Int = byLane.getValue(lane).size
}

class PooledTaskScheduler(
    private val tasks: TaskRepository,
    private val workers: CognitiveWorkerPool,
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
) : TaskSchedulingEngine {
    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative)
    }

    override suspend fun scheduleOnce(limit: Int): TaskScheduleResult {
        require(limit > 0)
        val scanTime = now()
        val runnable = tasks.listRunnable(scanTime, limit)
        var claimedCount = 0
        var dispatchedCount = 0
        val failures = mutableListOf<TaskId>()

        for (candidate in runnable) {
            val queued = normalizeRunnable(candidate, scanTime) ?: continue
            val slot = workers.select(queued.priority)
            val acquiredAt = now()
            val claimed = tasks.claim(
                id = queued.id,
                workerId = slot.workerId,
                acquiredAt = acquiredAt,
                leaseUntil = acquiredAt.plus(leaseDuration),
            ) ?: continue

            claimedCount += 1
            try {
                slot.dispatcher.dispatch(claimed)
                dispatchedCount += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += claimed.id
            }
        }

        return TaskScheduleResult(
            scanned = runnable.size,
            claimed = claimedCount,
            dispatched = dispatchedCount,
            dispatchFailures = failures,
        )
    }

    private suspend fun normalizeRunnable(
        task: LifeTask,
        at: Instant,
    ): LifeTask? = when (task.state) {
        TaskState.QUEUED -> task
        TaskState.RETRY_WAIT -> tasks.transition(
            id = task.id,
            expected = TaskState.RETRY_WAIT,
            next = TaskState.QUEUED,
            at = at,
        )
        else -> null
    }
}
