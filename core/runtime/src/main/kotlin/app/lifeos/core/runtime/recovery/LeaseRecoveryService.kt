package app.lifeos.core.runtime.recovery

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant

data class LeaseRecoveryResult(
    val scanned: Int,
    val recovered: Int,
    val skipped: Int,
    val recoveredTasks: List<LifeTask>,
)

class LeaseRecoveryService(
    private val tasks: TaskRepository,
    private val schedulerSignal: TaskSchedulerSignal,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun recoverExpired(limit: Int = 100): LeaseRecoveryResult {
        require(limit > 0) { "Lease recovery limit must be positive" }
        val recoveryTime = now()
        val expired = tasks.listExpiredLeases(recoveryTime, limit)
        val recovered = mutableListOf<LifeTask>()
        var skipped = 0

        for (task in expired) {
            val workerId = task.claimedBy
            val leaseExpiresAt = task.leaseExpiresAt
            if (workerId == null || leaseExpiresAt == null) {
                skipped += 1
                continue
            }

            val interrupted = tasks.interruptExpiredLease(
                id = task.id,
                expectedState = task.state,
                expectedWorkerId = workerId,
                expectedLeaseExpiresAt = leaseExpiresAt,
                at = recoveryTime,
            )
            if (interrupted == null) {
                skipped += 1
                continue
            }

            val recovering = tasks.transition(
                id = task.id,
                expected = TaskState.INTERRUPTED,
                next = TaskState.RECOVERING,
                at = recoveryTime,
            )
            if (recovering == null) {
                skipped += 1
                continue
            }

            val queued = tasks.transition(
                id = task.id,
                expected = TaskState.RECOVERING,
                next = TaskState.QUEUED,
                at = recoveryTime,
            )
            if (queued == null) {
                skipped += 1
                continue
            }

            recovered += queued
        }

        if (recovered.isNotEmpty()) {
            schedulerSignal.wake()
        }

        return LeaseRecoveryResult(
            scanned = expired.size,
            recovered = recovered.size,
            skipped = skipped,
            recoveredTasks = recovered,
        )
    }
}
