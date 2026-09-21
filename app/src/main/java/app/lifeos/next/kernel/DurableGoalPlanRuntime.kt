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
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRecord
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRepository
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingState
import app.lifeos.core.runtime.goal.GoalConvergenceCycleBinding
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionSource
import app.lifeos.core.runtime.goal.GoalOutcomeLearningHook
import app.lifeos.core.runtime.goal.ProductiveConvergenceNotReadyException
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
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.core.runtime.query.GoalOutcomeLookup
import java.time.Instant

sealed interface DurableGoalPlanAdmission {
    data class Ready(val permit: DurableGoalPlanPermit) : DurableGoalPlanAdmission
    data class Completed(
        val planId: String,
        val outcome: Photon? = null,
    ) : DurableGoalPlanAdmission
    data class Blocked(val reason: String) : DurableGoalPlanAdmission {
        init { require(reason.isNotBlank()) }
    }
}

data class DurableGoalPlanPermit(
    val blueprint: GoalPlanBlueprint,
    val preparation: GoalPlanExecutionPreparation.PreparedAction,
    val cycleBinding: GoalConvergenceCycleBinding? = null,
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
    private val convergence: GoalConvergenceDecisionSource,
    private val persistDerivedOutcome: suspend (Photon) -> PhotonSubmissionResult? = { null },
    private val outcomeLookup: GoalOutcomeLookup,
    private val cognitiveBindings: GoalCognitiveCycleBindingRepository? = null,
    private val outcomeLearning: GoalOutcomeLearningHook? = null,
    private val builder: GoalPlanBuilder = GoalPlanBuilder(),
    private val coordinator: GoalPlanExecutionCoordinator = GoalPlanExecutionCoordinator(ledger),
    private val projector: GoalStepDecisionProjector = GoalStepDecisionProjector(),
    private val traces: GoalDecisionTraceRecorder? = null,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(outcomeLearning == null || cognitiveBindings != null) {
            "Goal outcome learning requires durable cognitive-cycle bindings"
        }
    }

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
        traces?.recordPlan(blueprint.definition)
        val actionStep = blueprint.contracts.values.single { it.kind == GoalStepExecutionKind.ACTION }
        val actionState = state.stepStates.getValue(actionStep.stepId)

        if (actionState == GoalStepState.RUNNING) {
            val recovered = coordinator.prepareNext(blueprint, emptyMap(), now())
            if (recovered is GoalPlanExecutionPreparation.PreparedAction) {
                val persistedOutcome = recoverPersistedOutcome(context)
                if (persistedOutcome != null) {
                    completeWithPersistedOutcome(
                        DurableGoalPlanPermit(
                            blueprint = blueprint,
                            preparation = recovered,
                            cycleBinding = cognitiveBindings?.load(blueprint.definition.id)?.cycleBinding,
                        ),
                        persistedOutcome,
                    )
                    return DurableGoalPlanAdmission.Completed(
                        blueprint.definition.id.value,
                        persistedOutcome,
                    )
                }
            }
            return normalizePreparation(blueprint, recovered)
        }
        if (actionState == GoalStepState.COMPLETED) {
            val durableOutcomeId = state.outcomePhotonIds[actionStep.stepId]
            val persistedOutcome = recoverPersistedOutcome(
                context = context,
                durableOutcomeId = durableOutcomeId,
            )
            if (persistedOutcome != null) {
                ensureOutcomeLearning(
                    blueprint = blueprint,
                    outcomePhoton = persistedOutcome,
                    succeeded = true,
                    explicitBinding = cognitiveBindings?.load(blueprint.definition.id)?.cycleBinding,
                )
                return DurableGoalPlanAdmission.Completed(
                    blueprint.definition.id.value,
                    persistedOutcome,
                )
            }
            return DurableGoalPlanAdmission.Blocked(
                if (durableOutcomeId == null) {
                    "v7-completed-action-outcome-id-missing"
                } else {
                    "v7-completed-action-outcome-unavailable:${durableOutcomeId.value}"
                }
            )
        }

        val convergenceResult = try {
            convergence.decideBound(
                goal = context.goal,
                routing = context.routing,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
                goalPhotonRevision = context.goalPhotonRevision,
                at = now(),
            )
        } catch (blocked: ProductiveConvergenceNotReadyException) {
            return DurableGoalPlanAdmission.Blocked(
                "productive-convergence:${blocked.reason}"
            )
        }
        val checkpoint = convergenceResult.checkpoint
        traces?.recordConvergence(blueprint.definition, checkpoint)
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
        val cycleBinding = convergenceResult.cycleBinding
        if (cycleBinding != null) {
            persistConvergedBinding(
                blueprint = blueprint,
                binding = cycleBinding,
            )
        }
        return normalizePreparation(
            blueprint = blueprint,
            preparation = coordinator.prepareNext(
                blueprint = blueprint,
                decisions = mapOf(actionStep.stepId to decision),
                at = now(),
            ),
            cycleBinding = cycleBinding,
        )
    }

    suspend fun complete(
        permit: DurableGoalPlanPermit,
        result: GoalActionDispatchResult,
    ) {
        val outcome = persistedOutcome(result) ?: return
        completeWithPersistedOutcome(permit, outcome)
    }

    private suspend fun completeWithPersistedOutcome(
        permit: DurableGoalPlanPermit,
        outcomePhoton: Photon,
    ) {
        val action = permit.preparation.action
        coordinator.recordOutcome(
            blueprint = permit.blueprint,
            stepId = action.stepId,
            actionIdempotencyKey = action.idempotencyKey,
            outcomePhotonId = outcomePhoton.id,
            succeeded = true,
            sourceFingerprint = action.decisionSourceFingerprint,
            at = now(),
        )
        traces?.recordOutcome(
            definition = permit.blueprint.definition,
            outcome = outcomePhoton,
            succeeded = true,
        )
        ensureOutcomeLearning(
            blueprint = permit.blueprint,
            outcomePhoton = outcomePhoton,
            succeeded = true,
            explicitBinding = permit.cycleBinding,
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
        cycleBinding: GoalConvergenceCycleBinding? = null,
    ): DurableGoalPlanAdmission {
        val effectiveCycleBinding = cycleBinding
            ?: cognitiveBindings?.load(blueprint.definition.id)?.cycleBinding
        return when (preparation) {
        is GoalPlanExecutionPreparation.PreparedAction ->
            DurableGoalPlanAdmission.Ready(
                DurableGoalPlanPermit(
                    blueprint = blueprint,
                    preparation = preparation,
                    cycleBinding = effectiveCycleBinding,
                )
            )
        is GoalPlanExecutionPreparation.VerificationCompleted -> {
            when (val next = coordinator.prepareNext(blueprint, emptyMap(), now())) {
                GoalPlanExecutionPreparation.PlanCompleted ->
                    DurableGoalPlanAdmission.Blocked("v7-completed-plan-requires-bound-outcome")
                else -> DurableGoalPlanAdmission.Blocked("v7-post-verification:${next::class.simpleName}")
            }
        }
        GoalPlanExecutionPreparation.PlanCompleted ->
            DurableGoalPlanAdmission.Blocked("v7-completed-plan-requires-bound-outcome")
        is GoalPlanExecutionPreparation.ReplanRequired ->
            DurableGoalPlanAdmission.Blocked("v7-replan-required:${preparation.reason}")
        is GoalPlanExecutionPreparation.Waiting ->
            DurableGoalPlanAdmission.Blocked("v7-waiting:${preparation.reason}")
        }
    }

    private suspend fun persistConvergedBinding(
        blueprint: GoalPlanBlueprint,
        binding: GoalConvergenceCycleBinding,
    ): GoalCognitiveCycleBindingRecord? {
        val repository = cognitiveBindings ?: return null
        val planId = blueprint.definition.id
        val existing = repository.load(planId)
        if (existing != null) {
            require(existing.sourceGoalPhotonId == blueprint.definition.sourceGoalPhotonId)
            require(existing.sourceGoalPhotonRevision == blueprint.definition.sourceGoalPhotonRevision)
            require(existing.cycleBinding == binding) {
                "Goal plan already belongs to another cognitive cycle"
            }
            return existing
        }
        val created = GoalCognitiveCycleBindingRecord.converged(
            planId = planId,
            sourceGoalPhotonId = blueprint.definition.sourceGoalPhotonId,
            sourceGoalPhotonRevision = blueprint.definition.sourceGoalPhotonRevision,
            cycleBinding = binding,
        )
        if (repository.compareAndSet(planId, null, created)) return created
        val raced = requireNotNull(repository.load(planId)) {
            "Goal cognitive-cycle binding disappeared after concurrent creation"
        }
        require(raced.cycleBinding == binding) {
            "Concurrent goal cognitive-cycle binding belongs to another cycle"
        }
        return raced
    }

    private suspend fun ensureOutcomeLearning(
        blueprint: GoalPlanBlueprint,
        outcomePhoton: Photon,
        succeeded: Boolean,
        explicitBinding: GoalConvergenceCycleBinding?,
    ) {
        val repository = cognitiveBindings ?: return
        val learning = outcomeLearning ?: return
        val planId = blueprint.definition.id
        var record = repository.load(planId)
            ?: explicitBinding?.let { persistConvergedBinding(blueprint, it) }
            ?: return

        require(record.sourceGoalPhotonId == blueprint.definition.sourceGoalPhotonId)
        require(record.sourceGoalPhotonRevision == blueprint.definition.sourceGoalPhotonRevision)
        explicitBinding?.let {
            require(record.cycleBinding == it) {
                "Goal outcome learning cycle lineage mismatch"
            }
        }

        if (record.state < GoalCognitiveCycleBindingState.OUTCOME_RECORDED) {
            val next = record.recordOutcome(outcomePhoton)
            record = if (repository.compareAndSet(planId, record.revision, next)) {
                next
            } else {
                requireNotNull(repository.load(planId))
            }
        }
        require(record.outcomePhotonId == outcomePhoton.id)
        require(record.outcomePhotonRevision == outcomePhoton.revision)

        if (record.state == GoalCognitiveCycleBindingState.LEARNED) return

        val receipt = learning.learn(
            binding = record.cycleBinding,
            outcome = outcomePhoton,
            succeeded = succeeded,
        )
        val learned = record.markLearned(
            outcomeWorldSnapshotId = receipt.outcomeWorldSnapshotId,
            learningWatermarkRevision = receipt.learningWatermarkRevision,
        )
        if (!repository.compareAndSet(planId, record.revision, learned)) {
            val raced = requireNotNull(repository.load(planId))
            require(raced.state == GoalCognitiveCycleBindingState.LEARNED) {
                "Goal cognitive-cycle learning completion lost a CAS race"
            }
            require(raced.outcomeWorldSnapshotId == receipt.outcomeWorldSnapshotId)
        }
    }

    /**
     * If a process died after an executor persisted its result but before the V7 transition was
     * appended, the exact Goal Photon provenance lets us bind that result instead of repeating the
     * action. Only intent-specific final-result shapes are accepted; scene/intermediate photons and
     * archived failed reminders cannot satisfy recovery.
     */
    private suspend fun recoverPersistedOutcome(
        context: GoalActionContext,
        durableOutcomeId: PhotonId? = null,
    ): Photon? {
        if (durableOutcomeId != null) {
            outcomeLookup.exactOutcome(durableOutcomeId)?.let { exact ->
                require(exact.id == durableOutcomeId) {
                    "Exact goal outcome lookup returned another Photon id"
                }
                require(exact.phase != PhotonPhase.ARCHIVED) {
                    "Durable goal outcome is archived"
                }
                require(context.goalPhotonId in exact.provenance.parentIds) {
                    "Durable goal outcome lost Goal Photon provenance"
                }
                require(isFinalOutcomeFor(context.goal.intent, exact)) {
                    "Durable goal outcome shape no longer matches the action intent"
                }
                return exact
            }
        }

        return outcomeLookup.candidates(
            goalPhotonId = context.goalPhotonId,
            limit = MAX_OUTCOME_RECOVERY_CANDIDATES,
        )
            .asSequence()
            .filter { candidate -> durableOutcomeId == null || candidate.id == durableOutcomeId }
            .filter { it.phase != PhotonPhase.ARCHIVED }
            .filter { context.goalPhotonId in it.provenance.parentIds }
            .filter { candidate -> isFinalOutcomeFor(context.goal.intent, candidate) }
            .sortedWith(
                compareBy<Photon> { it.provenance.createdAt }
                    .thenBy { it.id.value }
                    .thenBy { it.revision }
            )
            .lastOrNull()
    }

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

    private suspend fun persistedOutcome(result: GoalActionDispatchResult): Photon? = when {
        result.imageGeneration is ImageGenerationResult.Generated ->
            result.imageGeneration.value.image.photon
        result.localImageTransform is LocalImageTransformExecutionResult.Transformed ->
            result.localImageTransform.output.photon
        result.localKnowledge is LocalKnowledgeExecutionResult.Produced ->
            result.localKnowledge.output.photon
        result.localDeepSearch is LocalDeepSearchExecutionResult.Produced ->
            result.localDeepSearch.output.photon
        result.localSchedule is LocalScheduleExecutionResult.Scheduled ->
            result.localSchedule.output.photon
        result.localCommunication is LocalCommunicationExecutionResult.Prepared -> {
            val outcome = communicationPreparationOutcome(result.localCommunication.share)
            persistDerivedOutcome(outcome)?.photon
        }
        else -> null
    }

    private fun communicationPreparationOutcome(share: LocalSharePreparation): Photon = Photon(
        id = PhotonId(
            "communication-preparation-" + StableFieldIds.fingerprint(
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
        const val MAX_OUTCOME_RECOVERY_CANDIDATES: Int = 64
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
