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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    fun select(
        priority: TaskPriority,
        excludedWorkerIds: Set<WorkerId> = emptySet(),
    ): CognitiveWorkerSlot? {
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
            )
        }
        preferred.forEach { lane ->
            val candidates = byLane.getValue(lane).filterNot { it.workerId in excludedWorkerIds }
            if (candidates.isNotEmpty()) {
                val index = Math.floorMod(
                    cursors.getValue(lane).getAndIncrement(),
                    candidates.size,
                )
                return candidates[index]
            }
        }
        return null
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
        val claimed = mutableListOf<Pair<CognitiveWorkerSlot, LifeTask>>()
        val usedWorkers = linkedSetOf<WorkerId>()

        for (candidate in runnable) {
            val queued = normalizeRunnable(candidate, scanTime) ?: continue
            val slot = workers.select(queued.priority, usedWorkers) ?: continue
            val acquiredAt = now()
            val owned = tasks.claim(
                id = queued.id,
                workerId = slot.workerId,
                acquiredAt = acquiredAt,
                leaseUntil = acquiredAt.plus(leaseDuration),
            ) ?: continue
            usedWorkers += slot.workerId
            claimed += slot to owned
            if (claimed.size >= workers.slots.size) break
        }

        val results = coroutineScope {
            claimed.map { (slot, task) ->
                async {
                    try {
                        slot.dispatcher.dispatch(task)
                        task.id to true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        task.id to false
                    }
                }
            }.awaitAll()
        }
        val failures = results.filterNot { it.second }.map { it.first }

        return TaskScheduleResult(
            scanned = runnable.size,
            claimed = claimed.size,
            dispatched = results.count { it.second },
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
