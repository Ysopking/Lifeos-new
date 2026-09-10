package app.lifeos.next.kernel

import app.lifeos.core.runtime.goal.LocalReminderRecord

sealed interface LocalScheduleExecutionResult {
    data class Scheduled(
        val output: PhotonSubmissionResult,
        val record: LocalReminderRecord,
    ) : LocalScheduleExecutionResult

    data class Blocked(val reason: String) : LocalScheduleExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val message: String,
        val output: PhotonSubmissionResult? = null,
    ) : LocalScheduleExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
