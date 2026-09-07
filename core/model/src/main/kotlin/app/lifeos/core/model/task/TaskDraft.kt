package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId

data class TaskDraft(
    val type: TaskType,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val inputPhotonIds: Set<PhotonId> = emptySet(),
    val inputPhotonRevisions: Map<PhotonId, Long> = emptyMap(),
    val idempotencyKey: String,
    val maxAttempts: Int = 3,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
        require(inputPhotonRevisions.keys.all { it in inputPhotonIds }) {
            "Pinned photon revisions must reference declared task inputs"
        }
        require(inputPhotonRevisions.values.all { it > 0 }) {
            "Pinned photon revisions must be positive"
        }
        require(maxAttempts > 0) { "Task maxAttempts must be positive" }
    }
}
