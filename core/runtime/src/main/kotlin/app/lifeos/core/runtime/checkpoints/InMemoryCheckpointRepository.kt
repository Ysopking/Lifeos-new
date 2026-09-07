package app.lifeos.core.runtime.checkpoints

import app.lifeos.core.model.checkpoint.CheckpointId
import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.checkpoint.SaveCheckpointResult
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.TaskId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryCheckpointRepository : CheckpointRepository {
    private val mutex = Mutex()
    private val checkpoints = linkedMapOf<CheckpointId, TaskCheckpoint>()
    private val taskSequenceIndex = mutableMapOf<Pair<TaskId, Long>, CheckpointId>()

    override suspend fun save(checkpoint: TaskCheckpoint): SaveCheckpointResult = mutex.withLock {
        checkpoints[checkpoint.id]?.let { existing ->
            return@withLock SaveCheckpointResult.Existing(existing)
        }

        val sequenceKey = checkpoint.taskId to checkpoint.sequence
        taskSequenceIndex[sequenceKey]?.let { existingId ->
            return@withLock SaveCheckpointResult.Existing(checkNotNull(checkpoints[existingId]))
        }

        checkpoints[checkpoint.id] = checkpoint
        taskSequenceIndex[sequenceKey] = checkpoint.id
        SaveCheckpointResult.Created(checkpoint)
    }

    override suspend fun get(id: CheckpointId): TaskCheckpoint? = mutex.withLock {
        checkpoints[id]
    }

    override suspend fun latest(taskId: TaskId): TaskCheckpoint? = mutex.withLock {
        checkpoints.values
            .asSequence()
            .filter { it.taskId == taskId }
            .maxWithOrNull(compareBy<TaskCheckpoint> { it.sequence }.thenBy { it.createdAt })
    }

    override suspend fun list(taskId: TaskId, limit: Int): List<TaskCheckpoint> = mutex.withLock {
        require(limit > 0) { "Checkpoint list limit must be positive" }
        checkpoints.values
            .asSequence()
            .filter { it.taskId == taskId }
            .sortedWith(compareByDescending<TaskCheckpoint> { it.sequence }.thenByDescending { it.createdAt })
            .take(limit)
            .toList()
    }

    override suspend fun delete(id: CheckpointId): Boolean = mutex.withLock {
        val removed = checkpoints.remove(id) ?: return@withLock false
        taskSequenceIndex.remove(removed.taskId to removed.sequence)
        true
    }
}
