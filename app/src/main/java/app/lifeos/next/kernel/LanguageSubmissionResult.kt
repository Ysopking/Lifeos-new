package app.lifeos.next.kernel

import app.lifeos.core.language.GoalPhoton
import app.lifeos.core.language.LanguageUnderstandingResult

data class LanguageSubmissionResult(
    val source: PhotonSubmissionResult,
    val understanding: LanguageUnderstandingResult,
    val goalPhoton: GoalPhoton,
    val goal: PhotonSubmissionResult,
) {
    val fullyQueued: Boolean get() = source.processingQueued && goal.processingQueued
}
