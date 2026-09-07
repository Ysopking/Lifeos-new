package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant

data class LifeTask(
    val id: TaskId = TaskId.new(),
    val type: TaskType,
    val state: TaskState = TaskState.CREATED,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val inputPhotonIds: Set<PhotonId> = emptySet(),
    val inputPhotonRevisions: Map<PhotonId, Long> = emptyMap(),
    val idempotencyKey: String,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val scheduledAt: Instant? = null,
    val claimedBy: WorkerId? = null,
    val leaseExpiresAt: Instant? = null,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
        require(inputPhotonRevisions.keys.all { it in inputPhotonIds }) {
            "Pinned photon revisions must reference declared task inputs"
        }
        require(inputPhotonRevisions.values.all { it > 0 }) {
            "Pinned photon revisions must be positive"
        }
        require(attempt >= 0) { "Task attempt must not be negative" }
        require(maxAttempts > 0) { "Task maxAttempts must be positive" }
        require(attempt <= maxAttempts) { "Task attempt must not exceed maxAttempts" }
        require(!updatedAt.isBefore(createdAt)) { "Task updatedAt must not precede createdAt" }
        require((claimedBy == null) == (leaseExpiresAt == null)) {
            "Task claim owner and lease expiry must be present together"
        }

        val requiresLease = state in setOf(
            TaskState.CLAIMED,
            TaskState.RUNNING,
            TaskState.CHECKPOINTED,
        )
        if (requiresLease) {
            require(claimedBy != null && leaseExpiresAt != null) {
                "Owned task state requires worker claim and lease"
            }
        } else {
            require(claimedBy == null && leaseExpiresAt == null) {
                "Unowned task state must not retain a worker lease"
            }
        }
    }
}
