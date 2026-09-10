package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhoton
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.runtime.capability.GoalCapabilityResolution

data class LanguageSubmissionResult(
    val source: PhotonSubmissionResult,
    val understanding: LanguageUnderstandingResult? = null,
    val goalPhoton: GoalPhoton? = null,
    val goal: PhotonSubmissionResult? = null,
    val routing: GoalCapabilityResolution? = null,
    val goalResume: GoalResumeExecutionResult? = null,
    val imageGeneration: ImageGenerationResult? = null,
    val localKnowledge: LocalKnowledgeExecutionResult? = null,
    val localDeepSearch: LocalDeepSearchExecutionResult? = null,
    val localSchedule: LocalScheduleExecutionResult? = null,
    val localCommunication: LocalCommunicationExecutionResult? = null,
    val languageFailure: String? = null,
) {
    val sourceStored: Boolean get() = true
    val languageUnderstood: Boolean get() = understanding != null && goalPhoton != null && languageFailure == null
    val effectiveGoal: GoalFrame? get() = when (val resume = goalResume) {
        is GoalResumeExecutionResult.Resumed -> resume.frame
        is GoalResumeExecutionResult.Blocked,
        is GoalResumeExecutionResult.Failed -> null
        null -> understanding?.goal?.takeUnless { it.intent == IntentType.CONTINUE }
    }
    val effectiveRouting: GoalCapabilityResolution? get() =
        (goalResume as? GoalResumeExecutionResult.Resumed)?.routing ?: routing
    val fullyQueued: Boolean get() =
        source.processingQueued &&
            goal?.processingQueued == true &&
            ((goalResume as? GoalResumeExecutionResult.Resumed)?.resumedGoal?.processingQueued != false) &&
            ((localKnowledge as? LocalKnowledgeExecutionResult.Produced)?.output?.processingQueued != false) &&
            ((localDeepSearch as? LocalDeepSearchExecutionResult.Produced)?.output?.processingQueued != false) &&
            ((localSchedule as? LocalScheduleExecutionResult.Scheduled)?.output?.processingQueued != false)
    val actionReady: Boolean get() = effectiveRouting?.ready == true
    val generatedImage: GeneratedImageResult? get() =
        (imageGeneration as? ImageGenerationResult.Generated)?.value
}
