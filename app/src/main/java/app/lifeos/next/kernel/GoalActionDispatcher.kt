package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.SemanticExecutionGate
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.agency.ExternalResourceReservation
import app.lifeos.core.runtime.agency.ExternalEffectRuntimeRegistry
import app.lifeos.core.runtime.agency.ExternalEffectExecutor
import app.lifeos.core.runtime.agency.ExternalActionContract
import app.lifeos.core.runtime.agency.EffectReceipt
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import kotlinx.coroutines.CancellationException

/** Immutable input for one already-resolved goal action. */
data class GoalActionContext(
    val goal: GoalFrame,
    val routing: GoalCapabilityResolution,
    val sourcePhoton: Photon,
    val goalPhotonId: PhotonId,
    val goalPhotonRevision: Long = 1L,
    val externalActionContract: ExternalActionContract? = null,
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
    val externalEffect: EffectReceipt? = null,
    val recoveredOutcome: Photon? = null,
)

class GoalActionDispatcher(
    private val executeKnowledge: suspend (GoalActionContext) -> LocalKnowledgeExecutionResult,
    private val executeDeepSearch: suspend (GoalActionContext) -> LocalDeepSearchExecutionResult,
    private val executeImageGeneration: suspend (GoalActionContext) -> ImageGenerationResult,
    private val executeImageTransform: suspend (GoalActionContext) -> LocalImageTransformExecutionResult,
    private val executeSchedule: suspend (GoalActionContext) -> LocalScheduleExecutionResult,
    private val prepareCommunication: suspend (GoalActionContext) -> LocalCommunicationExecutionResult,
    private val executionGuard: GoalActionExecutionGuard = GoalExecutionRuntimeRegistry.current(),
    private val externalEffectExecutor: ExternalEffectExecutor? =
        ExternalEffectRuntimeRegistry.currentOrNull(),
    private val durableRuntimeProvider: () -> DurableGoalPlanRuntime? =
        DurableGoalPlanRuntimeRegistry::currentOrNull,
    private val expandCapabilities: suspend (GoalActionContext) -> String =
        GenesisCapabilityExpansionRuntime::process,
) {
    suspend fun execute(context: GoalActionContext): GoalActionDispatchResult {
        val semanticAdmission = SemanticExecutionGate.evaluate(context.goal)
        if (!semanticAdmission.allowed) {
            return blocked(
                context.goal.intent,
                "semantic-admission:${semanticAdmission.reason}",
            )
        }
        if (context.externalActionContract != null &&
            !SemanticExecutionGate.externalEffectAllowed(context.goal)
        ) {
            return blocked(
                context.goal.intent,
                "semantic-admission:NO_EXTERNAL_SIDE_EFFECT_WITHOUT_EXECUTABLE_SEMANTIC_ACTION",
            )
        }

        DecisionTraceRuntimeRegistry.currentOrNull()?.recordCapabilityRouting(
            goalPhotonId = context.goalPhotonId,
            goalPhotonRevision = context.goalPhotonRevision,
            recordedAt = context.sourcePhoton.provenance.createdAt,
            resolution = context.routing,
        )

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

        val durableRuntime = durableRuntimeProvider()
        val durablePermit = when (val admission = durableRuntime?.prepare(context)) {
            null -> null
            is DurableGoalPlanAdmission.Ready -> admission.permit
            is DurableGoalPlanAdmission.Completed -> return GoalActionDispatchResult(
                recoveredOutcome = admission.outcome,
            )
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

            IntentType.CREATE_IMAGE -> {
                val rendered = executeImageGeneration(context)
                GoalActionDispatchResult(
                    imageGeneration = ImageArtifactLifecycleRuntimeRegistry.attach(context, rendered),
                )
            }

            IntentType.TRANSFORM_IMAGE -> {
                val transformed = executeImageTransform(context)
                GoalActionDispatchResult(
                    localImageTransform = ImageArtifactLifecycleRuntimeRegistry.attachTransform(
                        context,
                        transformed,
                    ),
                )
            }

            IntentType.SCHEDULE -> {
                val contract = context.externalActionContract
                if (contract == null) {
                    GoalActionDispatchResult(localSchedule = executeSchedule(context))
                } else {
                    GoalActionDispatchResult(
                        externalEffect = requireNotNull(externalEffectExecutor) {
                            "External action contract requires an installed ExternalEffectExecutor"
                        }.execute(bindReservation(contract, permit)),
                    )
                }
            }

            IntentType.COMMUNICATE -> {
                val contract = context.externalActionContract
                if (contract == null) {
                    GoalActionDispatchResult(localCommunication = prepareCommunication(context))
                } else {
                    GoalActionDispatchResult(
                        externalEffect = requireNotNull(externalEffectExecutor) {
                            "External action contract requires an installed ExternalEffectExecutor"
                        }.execute(bindReservation(contract, permit)),
                    )
                }
            }

            else -> GoalActionDispatchResult()
        }

        if (durableRuntime != null && durablePermit != null) {
            durableRuntime.complete(durablePermit, result)
        }
        executionGuard.settle(permit, result)
        return result
    }

    private fun bindReservation(
        contract: ExternalActionContract,
        permit: GoalActionExecutionPermit,
    ): ExternalActionContract {
        val reserved = permit as? GoalActionExecutionPermit.Reserved ?: return contract
        val reservation = ExternalResourceReservation(
            accountId = reserved.accountId,
            reservationId = reserved.reservation.id,
        )
        return contract.copy(
            resourceReservation = reservation,
            requiredOwnerPolicy = contract.requiredOwnerPolicy.copy(
                budgetAccountId = reserved.accountId,
                budgetReservationId = reserved.reservation.id,
            ),
        )
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
