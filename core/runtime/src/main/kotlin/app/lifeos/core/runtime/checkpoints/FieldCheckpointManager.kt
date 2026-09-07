package app.lifeos.core.runtime.checkpoints

import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.checkpoint.SaveCheckpointResult
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.ForceField
import java.time.Instant

data class FieldCheckpointState(
    val signature: String,
    val completedFieldIndexes: Set<Int>,
    val nextSequence: Long,
)

class FieldCheckpointStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class FieldCheckpointManager(
    private val checkpoints: CheckpointRepository?,
) {
    suspend fun load(
        taskId: TaskId,
        fields: List<ForceField>,
    ): FieldCheckpointState {
        val signature = FieldProgressCheckpointCodec.signature(fields)
        val repository = checkpoints
            ?: return FieldCheckpointState(signature, emptySet(), 1)

        val latest = try {
            repository.latest(taskId)
        } catch (error: Exception) {
            throw FieldCheckpointStorageException("Checkpoint lookup failed for task ${taskId.value}", error)
        } ?: return FieldCheckpointState(signature, emptySet(), 1)

        val decoded = try {
            FieldProgressCheckpointCodec.decode(latest.payload)
        } catch (_: Exception) {
            null
        }
        val compatibleIndexes = decoded
            ?.takeIf { it.fieldSignature == signature }
            ?.completedFieldIndexes
            ?.takeIf { indexes -> indexes.all { it in fields.indices } }
            ?: emptySet()

        return FieldCheckpointState(
            signature = signature,
            completedFieldIndexes = compatibleIndexes,
            nextSequence = latest.sequence + 1,
        )
    }

    suspend fun recordSuccess(
        taskId: TaskId,
        state: FieldCheckpointState,
        fieldIndex: Int,
        createdAt: Instant,
    ): FieldCheckpointState {
        require(fieldIndex >= 0) { "Completed field index must not be negative" }
        val completed = state.completedFieldIndexes + fieldIndex
        val repository = checkpoints
            ?: return state.copy(completedFieldIndexes = completed)

        val payload = FieldProgressCheckpointCodec.encode(
            FieldProgressCheckpoint(
                fieldSignature = state.signature,
                completedFieldIndexes = completed,
            )
        )
        val candidate = TaskCheckpoint(
            taskId = taskId,
            sequence = state.nextSequence,
            payload = payload,
            createdAt = createdAt,
        )
        val saved = try {
            repository.save(candidate)
        } catch (error: Exception) {
            throw FieldCheckpointStorageException("Checkpoint save failed for task ${taskId.value}", error)
        }

        val persisted = when (saved) {
            is SaveCheckpointResult.Created -> saved.checkpoint
            is SaveCheckpointResult.Existing -> saved.checkpoint
        }
        if (!persisted.payload.contentEquals(payload)) {
            throw FieldCheckpointStorageException(
                "Checkpoint sequence conflict for task ${taskId.value} at ${state.nextSequence}"
            )
        }

        return FieldCheckpointState(
            signature = state.signature,
            completedFieldIndexes = completed,
            nextSequence = state.nextSequence + 1,
        )
    }

    suspend fun clearBestEffort(taskId: TaskId) {
        val repository = checkpoints ?: return
        runCatching {
            repository.list(taskId, CLEANUP_LIMIT).forEach { repository.delete(it.id) }
        }
    }

    private companion object {
        const val CLEANUP_LIMIT = FieldProgressCheckpoint.MAX_FIELDS
    }
}
