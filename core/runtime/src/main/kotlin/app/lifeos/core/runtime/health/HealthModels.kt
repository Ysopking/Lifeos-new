package app.lifeos.core.runtime.health

import java.time.Instant

@JvmInline
value class HealthNodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Health node id must not be blank" }
        require(value.length <= 128) { "Health node id too long" }
    }

    override fun toString(): String = value
}

enum class HealthState {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    RECOVERING,
    QUARANTINED,
    DISABLED,
}

enum class HealthScope {
    KERNEL,
    RUNTIME,
    SCHEDULER,
    WORKER,
    FIELD,
    STORAGE_ITEM,
    STORAGE_ENGINE,
    MEMORY,
    CHAT,
    PLANNER,
    AUTOMATION,
    FINANCE,
    EXTERNAL_APP,
    BUILD_STUDIO,
    UNKNOWN,
}

enum class HealthFailureCategory {
    TRANSIENT,
    TIMEOUT,
    CANCELLATION,
    WORKER,
    FIELD,
    STORAGE_IO,
    DATA_CORRUPTION,
    RESOURCE,
    DEPENDENCY,
    STATE_INVARIANT,
    RECOVERY,
    UNKNOWN,
}

data class FailureClassification(
    val category: HealthFailureCategory,
    val scope: HealthScope,
    val recoverable: Boolean,
    val suggestedState: HealthState,
)

data class HealthObservation(
    val nodeId: HealthNodeId,
    val state: HealthState,
    val observedAt: Instant,
    val source: String,
    val message: String? = null,
    val classification: FailureClassification? = null,
    val actionable: Boolean = true,
) {
    init {
        require(source.isNotBlank()) { "Health observation source must not be blank" }
        require(state != HealthState.UNKNOWN || classification == null) {
            "Classified failures must not be recorded as UNKNOWN"
        }
    }
}

data class HealthNode(
    val id: HealthNodeId,
    val scope: HealthScope,
    val state: HealthState = HealthState.UNKNOWN,
    val consecutiveFailures: Int = 0,
    val totalFailures: Long = 0,
    val lastObservationAt: Instant? = null,
    val lastHealthyAt: Instant? = null,
    val lastMessage: String? = null,
) {
    init {
        require(consecutiveFailures >= 0) { "Consecutive failures must not be negative" }
        require(totalFailures >= 0) { "Total failures must not be negative" }
    }
}

data class HealthSnapshot(
    val nodes: List<HealthNode>,
    val capturedAt: Instant,
) {
    val overallState: HealthState = nodes
        .map { it.state }
        .maxByOrNull(::severityRank)
        ?: HealthState.UNKNOWN

    companion object {
        internal fun severityRank(state: HealthState): Int = when (state) {
            HealthState.UNKNOWN -> 0
            HealthState.HEALTHY -> 1
            HealthState.RECOVERING -> 2
            HealthState.DEGRADED -> 3
            HealthState.UNHEALTHY -> 4
            HealthState.QUARANTINED -> 5
            HealthState.DISABLED -> 6
        }
    }
}

private fun severityRank(state: HealthState): Int = HealthSnapshot.severityRank(state)
