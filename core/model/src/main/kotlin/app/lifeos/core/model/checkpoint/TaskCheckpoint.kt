package app.lifeos.core.model.checkpoint

import app.lifeos.core.model.task.TaskId
import java.time.Instant

data class TaskCheckpoint(
    val id: CheckpointId = CheckpointId.new(),
    val taskId: TaskId,
    val sequence: Long,
    val runtimeGeneration: Long = 0,
    val payload: ByteArray,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(sequence > 0) { "Checkpoint sequence must be positive" }
        require(runtimeGeneration >= 0) { "Runtime generation must not be negative" }
        require(payload.isNotEmpty()) { "Checkpoint payload must not be empty" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Checkpoint payload exceeds size limit" }
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}

sealed interface SaveCheckpointResult {
    val checkpoint: TaskCheckpoint

    data class Created(override val checkpoint: TaskCheckpoint) : SaveCheckpointResult
    data class Existing(override val checkpoint: TaskCheckpoint) : SaveCheckpointResult
}

data class CheckpointLoadReport(
    val checkpoints: List<TaskCheckpoint>,
    val unreadableEntries: List<String>,
) {
    init {
        require(checkpoints.map { it.id }.distinct().size == checkpoints.size) {
            "Checkpoint load report must not contain duplicate ids"
        }
        require(unreadableEntries.distinct().size == unreadableEntries.size) {
            "Unreadable checkpoint entries must be unique"
        }
    }
}

interface CheckpointRepository {
    suspend fun save(checkpoint: TaskCheckpoint): SaveCheckpointResult
    suspend fun get(id: CheckpointId): TaskCheckpoint?
    suspend fun latest(taskId: TaskId): TaskCheckpoint?
    suspend fun list(taskId: TaskId, limit: Int = 20): List<TaskCheckpoint>
    suspend fun delete(id: CheckpointId): Boolean
}

/** Read-only boot/recovery snapshot boundary; ordinary checkpoint stores need not expose it. */
interface CheckpointSnapshotRepository : CheckpointRepository {
    suspend fun loadReport(): CheckpointLoadReport
}
