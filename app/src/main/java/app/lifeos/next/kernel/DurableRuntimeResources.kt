package app.lifeos.next.kernel

import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline

internal data class DurableRuntimeResources(
    val pipeline: DurableCognitivePipeline,
    val taskRepository: TaskRepository,
    val checkpointRepository: CheckpointRepository,
)
