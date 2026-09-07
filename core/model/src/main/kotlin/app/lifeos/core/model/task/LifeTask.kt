package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId
import java.time.Instant

data class LifeTask(
    val id: TaskId = TaskId.new(),
    val type: TaskType,
    val state: TaskState = TaskState.CREATED,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val inputPhotonIds: Set<PhotonId> = emptySet(),
    val idempotencyKey: String,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val scheduledAt: Instant? = null,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
        require(attempt >= 0) { "Task attempt must not be negative" }
        require(maxAttempts > 0) { "Task maxAttempts must be positive" }
        require(attempt <= maxAttempts) { "Task attempt must not exceed maxAttempts" }
        require(!updatedAt.isBefore(createdAt)) { "Task updatedAt must not precede createdAt" }
    }
}
