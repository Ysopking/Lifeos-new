package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhoton
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.agency.EffectReceipt
import app.lifeos.core.runtime.world.StateSufficiencyStatus

data class WorldFormulaLanguageRefinementTrace(
    val initialPlanFingerprint: String,
    val retrievalNeedsFingerprint: String?,
    val initialPerceptionNeedCount: Int,
    val initialClarificationNeedCount: Int,
    val secondPassApplied: Boolean,
    val targetedContextItemCount: Int,
    val finalPlanFingerprint: String,
    val finalStateStatus: StateSufficiencyStatus?,
    val finalWorldGapIds: List<String>,
    val finalClarificationNeedIds: List<String>,
    val finalInterpretationReady: Boolean,
    val worldEvidenceFingerprint: String?,
) {
    init {
        require(initialPlanFingerprint.isNotBlank())
        require(retrievalNeedsFingerprint == null || retrievalNeedsFingerprint.isNotBlank())
        require(initialPerceptionNeedCount >= 0)
        require(initialClarificationNeedCount >= 0)
        require(targetedContextItemCount >= 0)
        require(finalPlanFingerprint.isNotBlank())
        require(finalWorldGapIds == finalWorldGapIds.distinct().sorted())
        require(
            finalClarificationNeedIds ==
                finalClarificationNeedIds.distinct().sorted()
        )
        require(secondPassApplied == (retrievalNeedsFingerprint != null))
        require(secondPassApplied || targetedContextItemCount == 0)
    }

    val executionAuthority: Boolean
        get() = false

    val directWorldStateMutationAllowed: Boolean
        get() = false
}

data class LanguageSubmissionResult(
    val source: PhotonSubmissionResult,
    val understanding: LanguageUnderstandingResult? = null,
    val goalPhoton: GoalPhoton? = null,
    val goal: PhotonSubmissionResult? = null,
    val routing: GoalCapabilityResolution? = null,
    val goalResume: GoalResumeExecutionResult? = null,
    val imageGeneration: ImageGenerationResult? = null,
    val localImageTransform: LocalImageTransformExecutionResult? = null,
    val localKnowledge: LocalKnowledgeExecutionResult? = null,
    val localDeepSearch: LocalDeepSearchExecutionResult? = null,
    val localSchedule: LocalScheduleExecutionResult? = null,
    val localCommunication: LocalCommunicationExecutionResult? = null,
    val externalEffect: EffectReceipt? = null,
    val actionGraphExecution: SemanticActionGraphExecutionResult? = null,
    val worldFormulaLanguage: WorldFormulaLanguageRefinementTrace? = null,
    val languageFailure: String? = null,
) {
    init {
        val resolvedRouting = (goalResume as? GoalResumeExecutionResult.Resumed)?.routing ?: routing
        if (resolvedRouting != null) {
            ToolCenterCapabilityGapRuntimeRegistry.publish(resolvedRouting.blockingGaps)
        }
    }

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
            ((localImageTransform as? LocalImageTransformExecutionResult.Transformed)?.output?.processingQueued != false) &&
            ((localSchedule as? LocalScheduleExecutionResult.Scheduled)?.output?.processingQueued != false)
    val actionReady: Boolean get() =
        actionGraphExecution?.completed == true || effectiveRouting?.ready == true
    val generatedImage: GeneratedImageResult? get() =
        (imageGeneration as? ImageGenerationResult.Generated)?.value
}
