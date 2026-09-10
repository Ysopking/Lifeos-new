package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution

/** Immutable input for one already-resolved goal action. */
data class GoalActionContext(
    val goal: GoalFrame,
    val routing: GoalCapabilityResolution,
    val sourcePhoton: Photon,
    val goalPhotonId: PhotonId,
)

/**
 * One action result envelope for the currently executable private-v1 goal families.
 * New action families are added here instead of growing parallel branches in LifeOsKernel.
 */
data class GoalActionDispatchResult(
    val imageGeneration: ImageGenerationResult? = null,
    val localKnowledge: LocalKnowledgeExecutionResult? = null,
    val localDeepSearch: LocalDeepSearchExecutionResult? = null,
)

class GoalActionDispatcher(
    private val executeKnowledge: suspend (GoalActionContext) -> LocalKnowledgeExecutionResult,
    private val executeDeepSearch: suspend (GoalActionContext) -> LocalDeepSearchExecutionResult,
    private val executeImageGeneration: suspend (GoalActionContext) -> ImageGenerationResult,
) {
    suspend fun execute(context: GoalActionContext): GoalActionDispatchResult {
        if (!context.routing.ready) {
            return if (context.goal.intent == IntentType.CREATE_IMAGE) {
                GoalActionDispatchResult(
                    imageGeneration = ImageGenerationResult.Blocked(
                        context.routing.blockingGaps
                            .map { gap -> "${gap.requirement.capabilityId.value}:${gap.type.name}" }
                            .ifEmpty { listOf("image goal is not action-ready") },
                    )
                )
            } else {
                GoalActionDispatchResult()
            }
        }

        return when (context.goal.intent) {
            IntentType.QUERY,
            IntentType.STORE_OR_REMEMBER -> GoalActionDispatchResult(
                localKnowledge = executeKnowledge(context),
            )

            IntentType.SEARCH -> GoalActionDispatchResult(
                localDeepSearch = executeDeepSearch(context),
            )

            IntentType.CREATE_IMAGE -> GoalActionDispatchResult(
                imageGeneration = executeImageGeneration(context),
            )

            else -> GoalActionDispatchResult()
        }
    }
}
