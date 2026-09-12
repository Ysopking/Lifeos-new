package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QuarantineEntry(
    val nodeId: HealthNodeId,
    val source: String,
    val reason: String,
    val quarantinedAt: Instant,
    val until: Instant? = null,
) {
    init {
        require(source.isNotBlank()) { "Quarantine source must not be blank" }
        require(reason.isNotBlank()) { "Quarantine reason must not be blank" }
        require(until == null || until.isAfter(quarantinedAt)) {
            "Quarantine end must be after start"
        }
    }
}

object QuarantineRegistryProcessRegistry {
    @Volatile
    private var installed: QuarantineRegistry? = null

    fun install(registry: QuarantineRegistry) {
        installed = registry
    }

    fun current(): QuarantineRegistry? = installed
}

/** Explicit quarantine state. Automatic quarantine policy is intentionally separate. */
class QuarantineRegistry {
    init {
        QuarantineRegistryProcessRegistry.install(this)
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<HealthNodeId, QuarantineEntry>()

    suspend fun quarantine(entry: QuarantineEntry): QuarantineEntry = mutex.withLock {
        entries[entry.nodeId] = entry
        entry
    }

    suspend fun active(nodeId: HealthNodeId, at: Instant): QuarantineEntry? = mutex.withLock {
        val entry = entries[nodeId] ?: return@withLock null
        if (entry.until != null && !at.isBefore(entry.until)) {
            entries.remove(nodeId)
            return@withLock null
        }
        entry
    }

    suspend fun release(nodeId: HealthNodeId): QuarantineEntry? = mutex.withLock {
        entries.remove(nodeId)
    }

    suspend fun snapshot(at: Instant): List<QuarantineEntry> = mutex.withLock {
        val expired = entries.values
            .filter { entry -> entry.until != null && !at.isBefore(entry.until) }
            .map { it.nodeId }
        expired.forEach(entries::remove)
        entries.values.sortedBy { it.nodeId.value }
    }
}
