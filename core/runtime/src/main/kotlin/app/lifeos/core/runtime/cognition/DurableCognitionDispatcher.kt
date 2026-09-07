package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import kotlinx.coroutines.CancellationException

data class DurableCognitiveDispatchResult(
    val workId: String,
    val task: LifeTask? = null,
    val skippedReason: String? = null,
) {
    val durable: Boolean get() = task != null
}

/**
 * Converts accepted cognitive work into the existing durable task model.
 * The durable task repository is the cross-process idempotency boundary;
 * the processing ledger additionally suppresses process-local duplicate submissions.
 */
class DurableCognitionDispatcher(
    private val taskEngine: DurableTaskEngine,
    private val ledger: CognitiveProcessingLedger = CognitiveProcessingLedger(),
) {
    suspend fun dispatch(item: CognitiveWorkItem): DurableCognitiveDispatchResult {
        val photonId = item.photonId
            ?: return DurableCognitiveDispatchResult(item.id, skippedReason = "missing-photon-id")
        val revision = item.photonRevision
            ?: return DurableCognitiveDispatchResult(item.id, skippedReason = "missing-photon-revision")

        val key = CognitiveProcessingKey(
            deltaId = item.triggeringDeltaId,
            moduleId = DURABLE_MODULE_ID,
            operation = DURABLE_OPERATION,
            inputRevision = revision,
        )
        if (!ledger.tryStart(key)) {
            return DurableCognitiveDispatchResult(
                workId = item.id,
                skippedReason = "already-processing-or-committed",
            )
        }

        return try {
            val task = taskEngine.submit(
                TaskDraft(
                    type = TaskType.PROCESS_PHOTON,
                    priority = item.priority.toTaskPriority(),
                    inputPhotonIds = setOf(photonId),
                    inputPhotonRevisions = mapOf(photonId to revision),
                    idempotencyKey = idempotencyKey(item, photonId.value, revision),
                )
            )
            check(ledger.commit(key)) { "Cognitive durable ledger lost processing state" }
            DurableCognitiveDispatchResult(workId = item.id, task = task)
        } catch (cancelled: CancellationException) {
            ledger.abort(key)
            throw cancelled
        } catch (error: Exception) {
            ledger.abort(key)
            throw error
        }
    }

    private fun CognitivePriority.toTaskPriority(): TaskPriority = when (this) {
        CognitivePriority.IDLE,
        CognitivePriority.BACKGROUND -> TaskPriority.BACKGROUND
        CognitivePriority.NORMAL -> TaskPriority.NORMAL
        CognitivePriority.HIGH -> TaskPriority.HIGH
        CognitivePriority.USER_BLOCKING -> TaskPriority.INTERACTIVE
        CognitivePriority.CRITICAL -> TaskPriority.CRITICAL
    }

    private fun idempotencyKey(
        item: CognitiveWorkItem,
        photonId: String,
        revision: Long,
    ): String = buildString {
        append("cognition:")
        append(item.triggeringDeltaId)
        append(":photon:")
        append(photonId)
        append(":revision:")
        append(revision)
        append(":pipeline:")
        append(PIPELINE_VERSION)
    }

    private companion object {
        const val DURABLE_MODULE_ID = "durable-task-engine"
        const val DURABLE_OPERATION = "submit-cognitive-work"
        const val PIPELINE_VERSION = 1
    }
}
