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
    val localImageTransform: LocalImageTransformExecutionResult? = null,
    val localKnowledge: LocalKnowledgeExecutionResult? = null,
    val localDeepSearch: LocalDeepSearchExecutionResult? = null,
    val localSchedule: LocalScheduleExecutionResult? = null,
    val localCommunication: LocalCommunicationExecutionResult? = null,
)

class GoalActionDispatcher(
    private val executeKnowledge: suspend (GoalActionContext) -> LocalKnowledgeExecutionResult,
    private val executeDeepSearch: suspend (GoalActionContext) -> LocalDeepSearchExecutionResult,
    private val executeImageGeneration: suspend (GoalActionContext) -> ImageGenerationResult,
    private val executeImageTransform: suspend (GoalActionContext) -> LocalImageTransformExecutionResult,
    private val executeSchedule: suspend (GoalActionContext) -> LocalScheduleExecutionResult,
    private val prepareCommunication: suspend (GoalActionContext) -> LocalCommunicationExecutionResult,
    private val executionGuard: GoalActionExecutionGuard = GoalExecutionRuntimeRegistry.current(),
) {
    suspend fun execute(context: GoalActionContext): GoalActionDispatchResult {
        if (!context.routing.ready) {
            val gapReason = context.routing.blockingGaps
                .joinToString(",") { gap -> "${gap.requirement.capabilityId.value}:${gap.type.name}" }
                .ifBlank { "goal-is-not-action-ready" }
            return when (context.goal.intent) {
                IntentType.CREATE_IMAGE -> GoalActionDispatchResult(
                    imageGeneration = ImageGenerationResult.Blocked(listOf(gapReason))
                )
                IntentType.TRANSFORM_IMAGE -> GoalActionDispatchResult(
                    localImageTransform = LocalImageTransformExecutionResult.Blocked(gapReason)
                )
                IntentType.SCHEDULE -> GoalActionDispatchResult(
                    localSchedule = LocalScheduleExecutionResult.Blocked(gapReason)
                )
                else -> GoalActionDispatchResult()
            }
        }

        val permit = executionGuard.prepare(context)
        if (permit is GoalActionExecutionPermit.Blocked) {
            return blocked(context.goal.intent, permit.reason)
        }

        val result = when (context.goal.intent) {
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

            IntentType.TRANSFORM_IMAGE -> GoalActionDispatchResult(
                localImageTransform = executeImageTransform(context),
            )

            IntentType.SCHEDULE -> GoalActionDispatchResult(
                localSchedule = executeSchedule(context),
            )

            IntentType.COMMUNICATE -> GoalActionDispatchResult(
                localCommunication = prepareCommunication(context),
            )

            else -> GoalActionDispatchResult()
        }
        executionGuard.settle(permit, result)
        return result
    }

    private fun blocked(intent: IntentType, reason: String): GoalActionDispatchResult = when (intent) {
        IntentType.QUERY,
        IntentType.STORE_OR_REMEMBER -> GoalActionDispatchResult(
            localKnowledge = LocalKnowledgeExecutionResult.Failed("blocked:$reason")
        )
        IntentType.SEARCH -> GoalActionDispatchResult(
            localDeepSearch = LocalDeepSearchExecutionResult.Failed("blocked:$reason")
        )
        IntentType.CREATE_IMAGE -> GoalActionDispatchResult(
            imageGeneration = ImageGenerationResult.Blocked(listOf(reason))
        )
        IntentType.TRANSFORM_IMAGE -> GoalActionDispatchResult(
            localImageTransform = LocalImageTransformExecutionResult.Blocked(reason)
        )
        IntentType.SCHEDULE -> GoalActionDispatchResult(
            localSchedule = LocalScheduleExecutionResult.Blocked(reason)
        )
        IntentType.COMMUNICATE -> GoalActionDispatchResult(
            localCommunication = LocalCommunicationExecutionResult.Blocked(reason)
        )
        else -> GoalActionDispatchResult()
    }
}
