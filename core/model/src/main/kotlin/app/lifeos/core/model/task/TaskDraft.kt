package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId

data class TaskDraft(
    val type: TaskType,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val inputPhotonIds: Set<PhotonId> = emptySet(),
    val idempotencyKey: String,
    val maxAttempts: Int = 3,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
        require(maxAttempts > 0) { "Task maxAttempts must be positive" }
    }
}
