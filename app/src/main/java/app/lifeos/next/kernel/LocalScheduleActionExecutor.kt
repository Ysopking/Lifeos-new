package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.runtime.goal.LocalScheduleGoalEngine
import app.lifeos.core.runtime.goal.LocalScheduleGoalResult
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Bridges one routed SCHEDULE action to the persistent reminder record and Android alarm adapter. */
class LocalScheduleActionExecutor(
    private val scheduler: LocalReminderScheduler,
    private val persistAndIngest: suspend (Photon) -> PhotonSubmissionResult,
    private val scheduleEngine: LocalScheduleGoalEngine = LocalScheduleGoalEngine(),
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun execute(context: GoalActionContext): LocalScheduleExecutionResult {
        val goal = context.goal
        if (goal.intent != IntentType.SCHEDULE) {
            return LocalScheduleExecutionResult.Failed(
                "Local schedule executor does not support ${goal.intent.name}",
            )
        }
        if (!context.routing.ready) {
            return LocalScheduleExecutionResult.Blocked("goal-is-not-action-ready")
        }
        if (!scheduler.canNotify()) {
            return LocalScheduleExecutionResult.Blocked("notification-permission-required")
        }

        return try {
            when (
                val result = scheduleEngine.execute(
                    goal = goal,
                    sourcePhoton = context.sourcePhoton,
                    goalPhotonId = context.goalPhotonId,
                    zoneId = zoneId(),
                    createdAt = context.sourcePhoton.provenance.createdAt,
                )
            ) {
                is LocalScheduleGoalResult.Blocked -> LocalScheduleExecutionResult.Blocked(result.reason)
                is LocalScheduleGoalResult.Unsupported -> LocalScheduleExecutionResult.Failed(
                    "Local schedule executor does not support ${result.intent.name}",
                )
                is LocalScheduleGoalResult.Scheduled -> {
                    val persisted = persistAndIngest(result.photon)
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
                        val failedSubmission = persistAndIngest(failedPhoton)
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
