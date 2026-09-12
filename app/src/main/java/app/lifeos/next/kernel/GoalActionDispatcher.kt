package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import kotlinx.coroutines.CancellationException

/** Immutable input for one already-resolved goal action. */
data class GoalActionContext(
    val goal: GoalFrame,
    val routing: GoalCapabilityResolution,
    val sourcePhoton: Photon,
    val goalPhotonId: PhotonId,
    val goalPhotonRevision: Long = 1L,
) {
    init { require(goalPhotonRevision > 0L) }
}

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
    private val durableRuntimeProvider: () -> DurableGoalPlanRuntime? =
        DurableGoalPlanRuntimeRegistry::currentOrNull,
    private val expandCapabilities: suspend (GoalActionContext) -> String =
        GenesisCapabilityExpansionRuntime::process,
) {
    suspend fun execute(context: GoalActionContext): GoalActionDispatchResult {
        // V15 is observational only: project the already-computed router result into the shared
        // trace before any capability expansion. The recorder owns no routing or execution authority.
        DecisionTraceRuntimeRegistry.currentOrNull()?.recordCapabilityRouting(
            goalPhotonId = context.goalPhotonId,
            goalPhotonRevision = context.goalPhotonRevision,
            recordedAt = context.sourcePhoton.provenance.createdAt,
            resolution = context.routing,
        )

        // Missing capability expansion is now always Genesis-first. Genesis selects the smallest safe
        // handoff; only TOOL_WORKSHOP handoffs may reach the bounded autonomous generator. BUILD_STUDIO
        // and other module paths stay explicit non-activating proposals until a real host/gate exists.
        if (!context.routing.ready) {
            val gapReason = context.routing.blockingGaps
                .joinToString(",") { gap -> "${gap.requirement.capabilityId.value}:${gap.type.name}" }
                .ifBlank { "goal-is-not-action-ready" }
            val expansionReason = try {
                expandCapabilities(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "genesis-expansion-failed:${error::class.simpleName}:${error.message.orEmpty().take(120)}"
            }
            return blocked(context.goal.intent, "$gapReason;$expansionReason")
        }

        // Production installs this runtime after kernel construction. Resolve it per execution rather than
        // capturing the registry in the constructor so the kernel cannot accidentally bypass late wiring.
        val durableRuntime = durableRuntimeProvider()
        val durablePermit = when (val admission = durableRuntime?.prepare(context)) {
            null -> null // unit/legacy composition only; production installs V7-F runtime.
            is DurableGoalPlanAdmission.Ready -> admission.permit
            is DurableGoalPlanAdmission.Completed -> return GoalActionDispatchResult()
            is DurableGoalPlanAdmission.Blocked -> return blocked(context.goal.intent, admission.reason)
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

        // Bind the already-persisted outcome to V7 before settling resource usage. If settlement is
        // interrupted, restart sees a completed plan and cannot repeat the host/user-visible action.
        if (durableRuntime != null && durablePermit != null) {
            durableRuntime.complete(durablePermit, result)
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
