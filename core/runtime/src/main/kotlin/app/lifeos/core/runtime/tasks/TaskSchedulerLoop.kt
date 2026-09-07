package app.lifeos.core.runtime.tasks

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TaskSchedulerLoop(
    private val scope: CoroutineScope,
    private val scheduler: TaskScheduler,
    private val wakeSource: TaskSchedulerWakeSource,
    private val batchSize: Int = 16,
    private val rescanInterval: Duration = Duration.ofSeconds(30),
) {
    init {
        require(batchSize > 0) { "Scheduler batch size must be positive" }
        require(!rescanInterval.isZero && !rescanInterval.isNegative) {
            "Scheduler rescan interval must be positive"
        }
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                drainRunnableWork()
                withTimeoutOrNull(rescanInterval.toMillis()) {
                    wakeSource.awaitWake()
                }
            }
        }
    }

    suspend fun stop() {
        val current = job ?: return
        job = null
        current.cancelAndJoin()
    }

    private suspend fun drainRunnableWork() {
        while (currentCoroutineContext().isActive) {
            val result = scheduler.scheduleOnce(batchSize)
            if (result.claimed == 0 || result.scanned < batchSize) return
        }
    }
}
