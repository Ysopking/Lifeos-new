package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.worker.WorkerId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class TaskScheduleResult(
    val scanned: Int,
    val claimed: Int,
    val dispatched: Int,
    val dispatchFailures: List<TaskId>,
)

fun interface TaskSchedulingEngine {
    suspend fun scheduleOnce(limit: Int): TaskScheduleResult
}

suspend fun TaskSchedulingEngine.scheduleOnce(): TaskScheduleResult = scheduleOnce(limit = 16)

class TaskScheduler(
    private val tasks: TaskRepository,
    private val workerId: WorkerId,
    private val dispatcher: ClaimedTaskDispatcher,
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
) : TaskSchedulingEngine {
    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Task lease duration must be positive"
        }
    }

    override suspend fun scheduleOnce(limit: Int): TaskScheduleResult {
        require(limit > 0) { "Scheduler limit must be positive" }

        val scanTime = now()
        val runnable = tasks.listRunnable(scanTime, limit)
        var claimedCount = 0
        var dispatchedCount = 0
        val dispatchFailures = mutableListOf<TaskId>()

        for (candidate in runnable) {
            val queued = normalizeRunnable(candidate, scanTime) ?: continue
            val acquiredAt = now()
            val claimed = tasks.claim(
                id = queued.id,
                workerId = workerId,
                acquiredAt = acquiredAt,
                leaseUntil = acquiredAt.plus(leaseDuration),
            ) ?: continue

            claimedCount += 1
            try {
                dispatcher.dispatch(claimed)
                dispatchedCount += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dispatchFailures += claimed.id
            }
        }

        return TaskScheduleResult(
            scanned = runnable.size,
            claimed = claimedCount,
            dispatched = dispatchedCount,
            dispatchFailures = dispatchFailures,
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
