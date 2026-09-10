package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhoton
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
    val languageFailure: String? = null,
) {
    val sourceStored: Boolean get() = true
    val languageUnderstood: Boolean get() = understanding != null && goalPhoton != null && languageFailure == null
    val effectiveGoal: GoalFrame? get() =
        (goalResume as? GoalResumeExecutionResult.Resumed)?.frame ?: understanding?.goal
    val effectiveRouting: GoalCapabilityResolution? get() =
        (goalResume as? GoalResumeExecutionResult.Resumed)?.routing ?: routing
    val fullyQueued: Boolean get() =
        source.processingQueued &&
            goal?.processingQueued == true &&
            ((goalResume as? GoalResumeExecutionResult.Resumed)?.resumedGoal?.processingQueued != false)
    val actionReady: Boolean get() = effectiveRouting?.ready == true
    val generatedImage: GeneratedImageResult? get() =
        (imageGeneration as? ImageGenerationResult.Generated)?.value
}
