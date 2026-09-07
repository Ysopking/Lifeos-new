package app.lifeos.core.runtime.recovery

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LeaseRecoveryLoop(
    private val scope: CoroutineScope,
    private val recovery: LeaseRecoveryService,
    private val interval: Duration = Duration.ofSeconds(30),
    private val batchSize: Int = 100,
) {
    init {
        require(!interval.isZero && !interval.isNegative) {
            "Lease recovery interval must be positive"
        }
        require(batchSize > 0) { "Lease recovery batch size must be positive" }
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                drainExpiredLeases()
                delay(interval.toMillis())
            }
        }
    }

    suspend fun stop() {
        val current = job ?: return
        job = null
        current.cancelAndJoin()
    }

    private suspend fun drainExpiredLeases() {
        while (currentCoroutineContext().isActive) {
            val result = recovery.recoverExpired(batchSize)
            if (result.scanned < batchSize || result.recovered == 0) return
        }
    }
}
