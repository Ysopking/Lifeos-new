package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.goal.GoalPlanBlueprint
import app.lifeos.core.runtime.goal.GoalPlanBuildResult
import app.lifeos.core.runtime.goal.GoalPlanBuilder
import app.lifeos.core.runtime.goal.GoalPlanExecutionCoordinator
import app.lifeos.core.runtime.goal.GoalPlanExecutionPreparation
import app.lifeos.core.runtime.goal.GoalStepDecisionProjectionResult
import app.lifeos.core.runtime.goal.GoalStepDecisionProjector
import app.lifeos.core.runtime.goal.GoalStepExecutionKind
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface DurableGoalPlanAdmission {
    data class Ready(val permit: DurableGoalPlanPermit) : DurableGoalPlanAdmission
    data class Completed(val planId: String) : DurableGoalPlanAdmission
    data class Blocked(val reason: String) : DurableGoalPlanAdmission {
        init { require(reason.isNotBlank()) }
    }
}

data class DurableGoalPlanPermit(
    val blueprint: GoalPlanBlueprint,
    val preparation: GoalPlanExecutionPreparation.PreparedAction,
) {
    val goalPhotonId: PhotonId get() = blueprint.definition.sourceGoalPhotonId
}

/**
 * APK bridge for V7-B..F. It creates/reuses the encrypted durable plan, obtains a real persisted V5
 * decision, persists RUNNING before the dispatcher may execute, and binds a persisted output Photon
 * back to the exact action before completing the internal verification step.
 */
class DurableGoalPlanRuntime(
    private val ledger: DurableGoalPlanLedger,
    private val convergence: GoalConvergenceDecisionProvider,
    private val builder: GoalPlanBuilder = GoalPlanBuilder(),
    private val coordinator: GoalPlanExecutionCoordinator = GoalPlanExecutionCoordinator(ledger),
    private val projector: GoalStepDecisionProjector = GoalStepDecisionProjector(),
    private val now: () -> Instant = Instant::now,
) {
    private val pendingByGoal = ConcurrentHashMap<PhotonId, DurableGoalPlanPermit>()

    suspend fun prepare(context: GoalActionContext): DurableGoalPlanAdmission {
        val built = builder.build(
            goal = context.goal,
            sourceGoalPhotonId = context.goalPhotonId,
            sourceGoalPhotonRevision = context.goalPhotonRevision,
            createdAt = context.sourcePhoton.provenance.createdAt,
        )
        val blueprint = when (built) {
            is GoalPlanBuildResult.Blocked -> return DurableGoalPlanAdmission.Blocked(built.reason)
            is GoalPlanBuildResult.Built -> built.blueprint
        }
        var state = ledger.create(blueprint.definition)
        val actionStep = blueprint.contracts.values.single { it.kind == GoalStepExecutionKind.ACTION }
        val actionState = state.stepStates.getValue(actionStep.stepId)

        if (actionState == GoalStepState.RUNNING || actionState == GoalStepState.COMPLETED) {
            return normalizePreparation(blueprint, coordinator.prepareNext(blueprint, emptyMap(), now()))
        }

        val checkpoint = convergence.decide(
            goal = context.goal,
            routing = context.routing,
            sourcePhoton = context.sourcePhoton,
            goalPhotonId = context.goalPhotonId,
            at = now(),
        )
        val decision = checkpoint.decision
        if (actionState in CONVERGENCE_MUTABLE_STATES) {
            when (val projection = projector.project(state, actionStep.stepId, decision, now())) {
                is GoalStepDecisionProjectionResult.Transitioned -> {
                    state = ledger.append(projection.transition).state
                }
                is GoalStepDecisionProjectionResult.Unchanged -> Unit
            }
        }
        if (decision.state != ConvergenceDecisionState.ACTIONABLE) {
            return DurableGoalPlanAdmission.Blocked(
                "v5-convergence:${decision.state.name.lowercase()}:${decision.reasons.joinToString("|")}",
            )
        }
        if (state.stepStates.getValue(actionStep.stepId) != GoalStepState.READY) {
            return DurableGoalPlanAdmission.Blocked(
                "v7-step-not-ready:${state.stepStates.getValue(actionStep.stepId).name.lowercase()}",
            )
        }
        return normalizePreparation(
            blueprint,
            coordinator.prepareNext(
                blueprint = blueprint,
                decisions = mapOf(actionStep.stepId to decision),
                at = now(),
            ),
        )
    }

    suspend fun complete(
        permit: DurableGoalPlanPermit,
        result: GoalActionDispatchResult,
    ) {
        val outcomeId = persistedOutcome(result) ?: run {
            pendingByGoal[permit.goalPhotonId] = permit
            return
        }
        completeWithPersistedOutcome(permit, outcomeId)
    }

    /** Communication becomes terminal only after Android handoff produced and persisted a receipt. */
    suspend fun recordCommunicationOutcome(goalPhotonId: PhotonId, receiptPhotonId: PhotonId) {
        val permit = pendingByGoal[goalPhotonId] ?: return
        completeWithPersistedOutcome(permit, receiptPhotonId)
    }

    private suspend fun completeWithPersistedOutcome(
        permit: DurableGoalPlanPermit,
        outcomePhotonId: PhotonId,
    ) {
        val action = permit.preparation.action
        coordinator.recordOutcome(
            blueprint = permit.blueprint,
            stepId = action.stepId,
            actionIdempotencyKey = action.idempotencyKey,
            outcomePhotonId = outcomePhotonId,
            succeeded = true,
            sourceFingerprint = action.decisionSourceFingerprint,
            at = now(),
        )
        // The verification step is internal and may complete immediately once the persisted outcome exists.
        val verification = coordinator.prepareNext(permit.blueprint, emptyMap(), now())
        if (verification is GoalPlanExecutionPreparation.VerificationCompleted) {
            coordinator.prepareNext(permit.blueprint, emptyMap(), now())
        }
        pendingByGoal.remove(permit.goalPhotonId)
    }

    private suspend fun normalizePreparation(
        blueprint: GoalPlanBlueprint,
        preparation: GoalPlanExecutionPreparation,
    ): DurableGoalPlanAdmission = when (preparation) {
        is GoalPlanExecutionPreparation.PreparedAction -> {
            val permit = DurableGoalPlanPermit(blueprint, preparation)
            pendingByGoal[permit.goalPhotonId] = permit
            DurableGoalPlanAdmission.Ready(permit)
        }
        is GoalPlanExecutionPreparation.VerificationCompleted -> {
            when (val next = coordinator.prepareNext(blueprint, emptyMap(), now())) {
                GoalPlanExecutionPreparation.PlanCompleted ->
                    DurableGoalPlanAdmission.Completed(blueprint.definition.id.value)
                else -> DurableGoalPlanAdmission.Blocked("v7-post-verification:${next::class.simpleName}")
            }
        }
        GoalPlanExecutionPreparation.PlanCompleted ->
            DurableGoalPlanAdmission.Completed(blueprint.definition.id.value)
        is GoalPlanExecutionPreparation.ReplanRequired ->
            DurableGoalPlanAdmission.Blocked("v7-replan-required:${preparation.reason}")
        is GoalPlanExecutionPreparation.Waiting ->
            DurableGoalPlanAdmission.Blocked("v7-waiting:${preparation.reason}")
    }

    private fun persistedOutcome(result: GoalActionDispatchResult): PhotonId? = when {
        result.imageGeneration is ImageGenerationResult.Generated ->
            result.imageGeneration.value.image.photon.id
        result.localImageTransform is LocalImageTransformExecutionResult.Transformed ->
            result.localImageTransform.output.photon.id
        result.localKnowledge is LocalKnowledgeExecutionResult.Produced ->
            result.localKnowledge.output.photon.id
        result.localDeepSearch is LocalDeepSearchExecutionResult.Produced ->
            result.localDeepSearch.output.photon.id
        result.localSchedule is LocalScheduleExecutionResult.Scheduled ->
            result.localSchedule.output.photon.id
        else -> null
    }

    private companion object {
        val CONVERGENCE_MUTABLE_STATES = setOf(
            GoalStepState.PLANNED,
            GoalStepState.READY,
            GoalStepState.WAITING_EVIDENCE,
            GoalStepState.WAITING_CAPABILITY,
        )
    }
}

object DurableGoalPlanRuntimeRegistry {
    @Volatile
    private var installed: DurableGoalPlanRuntime? = null

    fun install(runtime: DurableGoalPlanRuntime) {
        installed = runtime
    }

    fun currentOrNull(): DurableGoalPlanRuntime? = installed
}
