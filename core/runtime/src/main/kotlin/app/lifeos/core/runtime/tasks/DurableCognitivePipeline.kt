package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.Photon
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType

interface DurableProcessingPipeline {
    fun start()
    suspend fun stop()
    suspend fun submitPhoton(
        photon: Photon,
        priority: TaskPriority = TaskPriority.INTERACTIVE,
    ): LifeTask
}

class DurableCognitivePipeline(
    private val taskEngine: DurableTaskEngine,
    private val schedulerLoop: TaskSchedulerLoop,
) : DurableProcessingPipeline {
    override fun start() {
        schedulerLoop.start()
    }

    override suspend fun stop() {
        schedulerLoop.stop()
    }

    override suspend fun submitPhoton(
        photon: Photon,
        priority: TaskPriority,
    ): LifeTask = taskEngine.submit(
        TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            priority = priority,
            inputPhotonIds = setOf(photon.id),
            idempotencyKey = idempotencyKey(photon),
        )
    )

    private fun idempotencyKey(photon: Photon): String =
        "process-photon:${photon.id.value}:revision:${photon.revision}:pipeline:$PIPELINE_VERSION"

    private companion object {
        const val PIPELINE_VERSION = 1
    }
}
