package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.runtime.goal.LocalScheduleGoalEngine
import app.lifeos.core.runtime.goal.LocalScheduleGoalResult
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Bridges a routed SCHEDULE goal to the persistent reminder record and Android alarm adapter. */
class LocalScheduleActionExecutor(
    private val scheduler: LocalReminderScheduler,
    private val scheduleEngine: LocalScheduleGoalEngine = LocalScheduleGoalEngine(),
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun execute(
        kernel: LifeOsKernel,
        submission: LanguageSubmissionResult,
    ): LocalScheduleExecutionResult? {
        val goal = submission.effectiveGoal ?: return null
        val routing = submission.effectiveRouting ?: return null
        if (goal.intent != IntentType.SCHEDULE || !routing.ready) return null
        if (!scheduler.canNotify()) {
            return LocalScheduleExecutionResult.Blocked("notification-permission-required")
        }

        val resumed = submission.goalResume as? GoalResumeExecutionResult.Resumed
        val sourcePhoton = resumed?.sourcePhoton ?: submission.source.photon
        val goalPhotonId = resumed?.resumedGoal?.photon?.id
            ?: submission.goalPhoton?.photon?.id
            ?: return LocalScheduleExecutionResult.Failed("schedule-goal-photon-missing")

        return try {
            when (
                val result = scheduleEngine.execute(
                    goal = goal,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    zoneId = zoneId(),
                    createdAt = sourcePhoton.provenance.createdAt,
                )
            ) {
                is LocalScheduleGoalResult.Blocked -> LocalScheduleExecutionResult.Blocked(result.reason)
                is LocalScheduleGoalResult.Unsupported -> LocalScheduleExecutionResult.Failed(
                    "Local schedule executor does not support ${result.intent.name}",
                )
                is LocalScheduleGoalResult.Scheduled -> {
                    val persisted = kernel.persistAndIngest(result.photon)
                    try {
                        scheduler.schedule(result.photon.id, result.record)
                        LocalScheduleExecutionResult.Scheduled(
                            output = persisted,
                            record = result.record,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failedPhoton = result.photon.copy(
                            revision = result.photon.revision + 1,
                            phase = PhotonPhase.ARCHIVED,
                            tags = (result.photon.tags - "scheduled") + setOf("schedule-failed"),
                        )
                        val failedSubmission = kernel.persistAndIngest(failedPhoton)
                        LocalScheduleExecutionResult.Failed(
                            message = error.message ?: error::class.simpleName ?: "alarm scheduling failed",
                            output = failedSubmission,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalScheduleExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local schedule execution failed",
            )
        }
    }
}
