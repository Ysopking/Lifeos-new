package app.lifeos.core.runtime.cognition

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SchedulerOfferResult(
    val accepted: Boolean,
    val evictedWorkId: String? = null,
)

class CognitiveScheduler(
    private val maxQueued: Int = 1024,
) {
    init {
        require(maxQueued > 0) { "Scheduler queue capacity must be positive" }
    }

    private val mutex = Mutex()
    private val queue = linkedMapOf<String, CognitiveWorkItem>()

    suspend fun offer(item: CognitiveWorkItem): SchedulerOfferResult = mutex.withLock {
        if (item.id in queue) return@withLock SchedulerOfferResult(accepted = true)
        if (queue.size < maxQueued) {
            queue[item.id] = item
            return@withLock SchedulerOfferResult(accepted = true)
        }

        val worst = queue.values.minWithOrNull(workComparator)
            ?: return@withLock SchedulerOfferResult(accepted = false)
        if (workComparator.compare(item, worst) <= 0) {
            return@withLock SchedulerOfferResult(accepted = false)
        }

        queue.remove(worst.id)
        queue[item.id] = item
        SchedulerOfferResult(accepted = true, evictedWorkId = worst.id)
    }

    suspend fun poll(): CognitiveWorkItem? = mutex.withLock {
        val next = queue.values.maxWithOrNull(workComparator) ?: return@withLock null
        queue.remove(next.id)
        next
    }

    suspend fun remove(workId: String): CognitiveWorkItem? = mutex.withLock {
        queue.remove(workId)
    }

    suspend fun size(): Int = mutex.withLock { queue.size }

    suspend fun snapshot(): List<CognitiveWorkItem> = mutex.withLock {
        queue.values.sortedWith(workComparator.reversed()).toList()
    }

    private companion object {
        val workComparator = compareBy<CognitiveWorkItem> { it.priority.rank }
            .thenBy { it.salience }
            .thenByDescending { it.enqueuedAt }
            .thenByDescending { it.id }
    }
}
