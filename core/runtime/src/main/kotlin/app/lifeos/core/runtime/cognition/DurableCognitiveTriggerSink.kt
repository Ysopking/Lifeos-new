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
 */
class DurableCognitiveTriggerSink(
    private val journal: CognitiveTriggerSink,
    private val photons: PhotonRepository,
    private val taskEngine: DurableTaskEngine,
    private val policy: CognitiveTriggerDispatchPolicy = CognitiveTriggerDispatchPolicy(),
) : CognitiveTriggerSink {
    override suspend fun emit(trigger: CognitiveTrigger): Boolean {
        if (trigger.type in policy.durableFeedbackTypes) {
            durabilize(trigger)
        }
        return journal.emit(trigger)
    }

    override suspend fun snapshot(): List<CognitiveTrigger> = journal.snapshot()

    private suspend fun durabilize(trigger: CognitiveTrigger) {
        val photonId = trigger.photonId ?: return
        val photon = try {
            photons.load(photonId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        } ?: return

        taskEngine.submit(
            TaskDraft(
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
        )
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
