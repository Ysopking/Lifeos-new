package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import kotlinx.coroutines.CancellationException

data class CognitiveTriggerDispatchPolicy(
    val durableFeedbackTypes: Set<CognitiveTriggerType> = setOf(
        CognitiveTriggerType.REEVALUATE,
        CognitiveTriggerType.CONVERGENCE,
    ),
) {
    init {
        require(CognitiveTriggerType.RECOVERY !in durableFeedbackTypes) {
            "Recovery triggers require RecoveryCoordinator integration, not photon reprocessing"
        }
        require(CognitiveTriggerType.QUARANTINE_REVIEW !in durableFeedbackTypes) {
            "Quarantine review must remain passive until HealthGraph integration"
        }
    }
}

/**
 * Persists all cognitive triggers while converting only explicitly safe feedback
 * types into durable photon reevaluation tasks. The durable task is created
 * before the trigger is journaled; task idempotency makes crash/retry replay safe.
 *
 * When durable cognition is capacity-bound, feedback uses the same admission boundary as live
 * photon work. A rejected feedback trigger is not journaled as accepted because that would claim
 * durable follow-up exists when no TaskStore record was created.
 */
class DurableCognitiveTriggerSink(
    private val journal: CognitiveTriggerSink,
    private val photons: PhotonRepository,
    private val taskEngine: DurableTaskEngine,
    private val policy: CognitiveTriggerDispatchPolicy = CognitiveTriggerDispatchPolicy(),
    admissionController: DurableCognitionAdmissionController? = null,
) : CognitiveTriggerSink {
    private val admissionController = admissionController
        ?: taskEngine.cognitionSnapshotRepository?.let { tasks ->
            DurableCognitionAdmissionController(
                tasks = tasks,
                taskEngine = taskEngine,
            )
        }

    override suspend fun emit(trigger: CognitiveTrigger): Boolean {
        if (trigger.type in policy.durableFeedbackTypes && !durabilize(trigger)) {
            return false
        }
        return journal.emit(trigger)
    }

    override suspend fun snapshot(): List<CognitiveTrigger> = journal.snapshot()

    private suspend fun durabilize(trigger: CognitiveTrigger): Boolean {
        val photonId = trigger.photonId ?: return true
        val photon = try {
            photons.load(photonId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return true
        } ?: return true

        val draft = TaskDraft(
            type = TaskType.REPROCESS_PHOTON,
            priority = trigger.type.toPriority(),
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = buildString {
                append("cognitive-trigger:")
                append(trigger.id)
                append(":photon:")
                append(photon.id.value)
                append(":revision:")
                append(photon.revision)
                append(":pipeline:")
                append(PIPELINE_VERSION)
            },
        )

        val controller = admissionController
        val task = if (controller != null) {
            controller.submit(draft)
        } else {
            taskEngine.submit(draft)
        }
        return task != null
    }

    private fun CognitiveTriggerType.toPriority(): TaskPriority = when (this) {
        CognitiveTriggerType.REEVALUATE -> TaskPriority.HIGH
        CognitiveTriggerType.CONVERGENCE -> TaskPriority.NORMAL
        CognitiveTriggerType.RECOVERY -> TaskPriority.CRITICAL
        CognitiveTriggerType.QUARANTINE_REVIEW -> TaskPriority.HIGH
    }

    private companion object {
        const val PIPELINE_VERSION = 1
    }
}
