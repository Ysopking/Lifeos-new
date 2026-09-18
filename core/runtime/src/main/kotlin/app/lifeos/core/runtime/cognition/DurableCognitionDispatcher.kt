package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRevisionRef
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
 * the processing ledger additionally suppresses concurrent process-local duplicates.
 */
class DurableCognitionDispatcher(
    private val taskEngine: DurableTaskEngine,
    private val ledger: CognitiveProcessingLedger = CognitiveProcessingLedger(),
    private val admissionController: DurableCognitionAdmissionController? = null,
    private val coverageIndex: CognitionCoverageIndex? = null,
) {
    suspend fun dispatch(item: CognitiveWorkItem): DurableCognitiveDispatchResult {
        val photonId = item.photonId
            ?: return DurableCognitiveDispatchResult(item.id, skippedReason = "missing-photon-id")
        val revision = item.photonRevision
            ?: return DurableCognitiveDispatchResult(item.id, skippedReason = "missing-photon-revision")
        val draft = TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            priority = item.priority.toTaskPriority(),
            inputPhotonIds = setOf(photonId),
            inputPhotonRevisions = mapOf(photonId to revision),
            idempotencyKey = idempotencyKey(item, photonId.value, revision),
        )
        val key = CognitiveProcessingKey(
            deltaId = item.triggeringDeltaId,
            moduleId = DURABLE_MODULE_ID,
            operation = DURABLE_OPERATION,
            inputRevision = revision,
        )

        when (ledger.state(key)) {
            ProcessingState.PROCESSING -> return DurableCognitiveDispatchResult(
                workId = item.id,
                skippedReason = "already-processing",
            )
            ProcessingState.COMMITTED -> {
                val task = submitDurably(draft)
                return DurableCognitiveDispatchResult(
                    workId = item.id,
                    task = task,
                    skippedReason = if (task == null) BACKPRESSURE_REASON else null,
                )
            }
            null -> Unit
        }

        if (!ledger.tryStart(key)) {
            return DurableCognitiveDispatchResult(
                workId = item.id,
                skippedReason = "already-processing",
            )
        }

        return try {
            val task = submitDurably(draft)
            if (task == null) {
                ledger.abort(key)
                DurableCognitiveDispatchResult(
                    workId = item.id,
                    skippedReason = BACKPRESSURE_REASON,
                )
            } else {
                check(ledger.commit(key)) { "Cognitive durable ledger lost processing state" }
                coverageIndex?.markCovered(PhotonRevisionRef(photonId, revision))
                DurableCognitiveDispatchResult(workId = item.id, task = task)
            }
        } catch (cancelled: CancellationException) {
            ledger.abort(key)
            throw cancelled
        } catch (error: Exception) {
            ledger.abort(key)
            throw error
        }
    }

    suspend fun dispatchBatch(items: List<CognitiveWorkItem>): List<DurableCognitiveDispatchResult> {
        if (items.isEmpty()) return emptyList()

        data class Prepared(
            val index: Int,
            val item: CognitiveWorkItem,
            val draft: TaskDraft,
            val key: CognitiveProcessingKey,
            val startedHere: Boolean,
        )

        val fixed = arrayOfNulls<DurableCognitiveDispatchResult>(items.size)
        val prepared = mutableListOf<Prepared>()

        items.forEachIndexed { index, item ->
            val photonId = item.photonId
            val revision = item.photonRevision
            if (photonId == null || revision == null) {
                fixed[index] = DurableCognitiveDispatchResult(
                    item.id,
                    skippedReason = if (photonId == null) "missing-photon-id" else "missing-photon-revision",
                )
                return@forEachIndexed
            }
            val draft = TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                priority = item.priority.toTaskPriority(),
                inputPhotonIds = setOf(photonId),
                inputPhotonRevisions = mapOf(photonId to revision),
                idempotencyKey = idempotencyKey(item, photonId.value, revision),
            )
            val key = CognitiveProcessingKey(
                deltaId = item.triggeringDeltaId,
                moduleId = DURABLE_MODULE_ID,
                operation = DURABLE_OPERATION,
                inputRevision = revision,
            )
            when (ledger.state(key)) {
                ProcessingState.PROCESSING -> fixed[index] = DurableCognitiveDispatchResult(
                    item.id,
                    skippedReason = "already-processing",
                )
                ProcessingState.COMMITTED -> prepared += Prepared(index, item, draft, key, false)
                null -> {
                    if (ledger.tryStart(key)) {
                        prepared += Prepared(index, item, draft, key, true)
                    } else {
                        fixed[index] = DurableCognitiveDispatchResult(
                            item.id,
                            skippedReason = "already-processing",
                        )
                    }
                }
            }
        }

        if (prepared.isNotEmpty()) {
            try {
                val tasks = admissionController?.submitBatch(prepared.map { it.draft })
                    ?: prepared.map { taskEngine.submit(it.draft) }
                prepared.forEachIndexed { preparedIndex, value ->
                    val task = tasks[preparedIndex]
                    if (task == null) {
                        if (value.startedHere) ledger.abort(value.key)
                        fixed[value.index] = DurableCognitiveDispatchResult(
                            workId = value.item.id,
                            skippedReason = BACKPRESSURE_REASON,
                        )
                    } else {
                        if (value.startedHere) {
                            check(ledger.commit(value.key)) {
                                "Cognitive durable ledger lost batch processing state"
                            }
                        }
                        val photonId = requireNotNull(value.item.photonId)
                        val revision = requireNotNull(value.item.photonRevision)
                        coverageIndex?.markCovered(PhotonRevisionRef(photonId, revision))
                        fixed[value.index] = DurableCognitiveDispatchResult(
                            workId = value.item.id,
                            task = task,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                prepared.filter { it.startedHere }.forEach { ledger.abort(it.key) }
                throw cancelled
            } catch (error: Exception) {
                prepared.filter { it.startedHere }.forEach { ledger.abort(it.key) }
                throw error
            }
        }

        return fixed.mapIndexed { index, result ->
            result ?: DurableCognitiveDispatchResult(
                workId = items[index].id,
                skippedReason = "batch-dispatch-unresolved",
            )
        }
    }

    private suspend fun submitDurably(draft: TaskDraft): LifeTask? {
        val controller = admissionController
        return if (controller != null) {
            controller.submit(draft)
        } else {
            taskEngine.submit(draft)
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
        const val BACKPRESSURE_REASON = "durable-backpressure"
    }
}
