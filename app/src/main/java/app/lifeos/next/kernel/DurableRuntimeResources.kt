package app.lifeos.next.kernel

import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.runtime.LifeOsRuntime

internal data class DurableRuntimeResources(
    val runtime: LifeOsRuntime,
    val taskRepository: TaskRepository,
    val checkpointRepository: CheckpointRepository,
)
