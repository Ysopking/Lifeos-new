package app.lifeos.next.kernel

import app.lifeos.core.language.GoalPhoton
import app.lifeos.core.language.LanguageUnderstandingResult

data class LanguageSubmissionResult(
    val source: PhotonSubmissionResult,
    val understanding: LanguageUnderstandingResult? = null,
    val goalPhoton: GoalPhoton? = null,
    val goal: PhotonSubmissionResult? = null,
    val languageFailure: String? = null,
) {
    val sourceStored: Boolean get() = true
    val languageUnderstood: Boolean get() = understanding != null && goalPhoton != null && languageFailure == null
    val fullyQueued: Boolean get() = source.processingQueued && goal?.processingQueued == true
}
