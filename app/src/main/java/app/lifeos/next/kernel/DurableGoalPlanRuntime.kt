package app.lifeos.next.kernel

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.goal.GoalPlanBlueprint
import app.lifeos.core.runtime.goal.GoalPlanBuildResult
import app.lifeos.core.runtime.goal.GoalPlanBuilder
import app.lifeos.core.runtime.goal.GoalPlanExecutionCoordinator
import app.lifeos.core.runtime.goal.GoalPlanExecutionPreparation
import app.lifeos.core.runtime.goal.GoalStepDecisionProjectionResult
import app.lifeos.core.runtime.goal.GoalStepDecisionProjector
import app.lifeos.core.runtime.goal.GoalStepExecutionKind
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.goal.LocalSharePreparation
import java.time.Instant

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
    private val persistDerivedOutcome: suspend (Photon) -> PhotonSubmissionResult? = { null },
    private val loadPersistedPhotons: suspend () -> List<Photon> = { emptyList() },
    private val builder: GoalPlanBuilder = GoalPlanBuilder(),
    private val coordinator: GoalPlanExecutionCoordinator = GoalPlanExecutionCoordinator(ledger),
    private val projector: GoalStepDecisionProjector = GoalStepDecisionProjector(),
    private val now: () -> Instant = Instant::now,
) {
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

        if (actionState == GoalStepState.RUNNING) {
            val recovered = coordinator.prepareNext(blueprint, emptyMap(), now())
            if (recovered is GoalPlanExecutionPreparation.PreparedAction) {
                val persistedOutcome = recoverPersistedOutcome(context)
                if (persistedOutcome != null) {
                    completeWithPersistedOutcome(
                        DurableGoalPlanPermit(blueprint, recovered),
                        persistedOutcome.id,
                    )
                    return DurableGoalPlanAdmission.Completed(blueprint.definition.id.value)
                }
            }
            return normalizePreparation(blueprint, recovered)
        }
        if (actionState == GoalStepState.COMPLETED) {
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
        val outcomeId = persistedOutcome(result) ?: return
        completeWithPersistedOutcome(permit, outcomeId)
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
    }

    private suspend fun normalizePreparation(
        blueprint: GoalPlanBlueprint,
        preparation: GoalPlanExecutionPreparation,
    ): DurableGoalPlanAdmission = when (preparation) {
        is GoalPlanExecutionPreparation.PreparedAction ->
            DurableGoalPlanAdmission.Ready(DurableGoalPlanPermit(blueprint, preparation))
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

    /**
     * If a process died after an executor persisted its result but before the V7 transition was
     * appended, the exact Goal Photon provenance lets us bind that result instead of repeating the
     * action. Only intent-specific final-result shapes are accepted; scene/intermediate photons and
     * archived failed reminders cannot satisfy recovery.
     */
    private suspend fun recoverPersistedOutcome(context: GoalActionContext): Photon? =
        loadPersistedPhotons()
            .asSequence()
            .filter { it.phase != PhotonPhase.ARCHIVED }
            .filter { context.goalPhotonId in it.provenance.parentIds }
            .filter { candidate -> isFinalOutcomeFor(context.goal.intent, candidate) }
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            .lastOrNull()

    private fun isFinalOutcomeFor(intent: IntentType, photon: Photon): Boolean = when (intent) {
        IntentType.QUERY -> "local-query-answer" in photon.tags
        IntentType.STORE_OR_REMEMBER -> "memory" in photon.tags
        IntentType.SEARCH -> "deepsearch-answer" in photon.tags
        IntentType.CREATE_IMAGE -> "image" in photon.tags && "generated" in photon.tags
        IntentType.TRANSFORM_IMAGE -> "image" in photon.tags && "transformed" in photon.tags
        IntentType.SCHEDULE -> "reminder" in photon.tags && "scheduled" in photon.tags
        IntentType.COMMUNICATE -> "share-preparation" in photon.tags
        else -> false
    }

    private suspend fun persistedOutcome(result: GoalActionDispatchResult): PhotonId? = when {
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
        result.localCommunication is LocalCommunicationExecutionResult.Prepared -> {
            val outcome = communicationPreparationOutcome(result.localCommunication.share)
            persistDerivedOutcome(outcome)?.photon?.id
        }
        else -> null
    }

    private fun communicationPreparationOutcome(share: LocalSharePreparation): Photon = Photon(
        id = PhotonId(
            "communication-preparation:" + StableFieldIds.fingerprint(
                "communication-preparation/v1",
                share.requestSourceId.value,
                share.requestGoalId.value,
                share.target.id.value,
                share.mediaType,
            )
        ),
        content = buildString {
            appendLine("communication-preparation/v1")
            appendLine("status=prepared")
            appendLine("targetPhotonId=${share.target.id.value}")
            append("mediaType=${share.mediaType}")
        },
        mimeType = COMMUNICATION_PREPARATION_MIME,
        phase = PhotonPhase.CONVERGED,
        semanticMass = 0.5,
        energy = 0.5,
        confidence = 1.0,
        provenance = Provenance(
            source = "local-share-preparation",
            actor = "DurableGoalPlanRuntime",
            createdAt = now(),
            parentIds = setOf(share.requestSourceId, share.requestGoalId, share.target.id),
        ),
        relations = setOf(
            PhotonRelation(share.requestSourceId, RelationType.DERIVED_FROM),
            PhotonRelation(share.requestGoalId, RelationType.REFERENCES),
            PhotonRelation(share.target.id, RelationType.REFERENCES),
        ),
        tags = setOf("communication", "share-preparation", "result"),
    )

    private companion object {
        const val COMMUNICATION_PREPARATION_MIME =
            "application/vnd.lifeos.communication-preparation+text"
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
