package app.lifeos.core.runtime.trace

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Non-authoritative V15 recorder over already-durable V7/V5/V14/V16/outcome evidence.
 *
 * A trace write is never allowed to upgrade, grant or reinterpret productive authority. If trace
 * persistence is unavailable the authoritative operation keeps its original result and diagnostics
 * receive an explicit Unavailable result instead of fabricated explanation data.
 */
class GoalDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun recordPlan(definition: GoalPlanDefinition): DecisionTraceRecordResult =
        record(stage = "goal-plan") {
            ledger.append(
                id = traceId(definition),
                nodes = foundationNodes(definition),
                links = listOf(
                    DecisionTraceLink(
                        from = planNode(definition).id,
                        to = goalNode(definition).id,
                        type = DecisionTraceLinkType.DERIVED_FROM,
                    )
                ),
            )
        }

    suspend fun recordConvergence(
        definition: GoalPlanDefinition,
        checkpoint: ConvergenceDecisionCheckpoint,
    ): DecisionTraceRecordResult = record(stage = "convergence") {
        val decision = decisionNode(definition, checkpoint)
        val candidates = checkpoint.decision.candidates
            .map { candidate ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
                    sourceType = "convergence-candidate",
                    sourceId = "${checkpoint.id.value}:${candidate.domainId.value}:${candidate.hypothesisId.value}",
                    sourceRevision = 1L,
                    recordedAt = definition.createdAt,
                )
            }
        val selected = checkpoint.decision.selectedHypothesisIds.mapTo(hashSetOf()) { it.value }
        val candidateLinks = checkpoint.decision.candidates.zip(candidates).flatMap { (candidate, node) ->
            buildList {
                add(
                    DecisionTraceLink(
                        from = node.id,
                        to = decision.id,
                        type = DecisionTraceLinkType.ALTERNATIVE_TO,
                    )
                )
                if (candidate.hypothesisId.value in selected) {
                    add(
                        DecisionTraceLink(
                            from = node.id,
                            to = decision.id,
                            type = DecisionTraceLinkType.SELECTED_BY,
                        )
                    )
                }
            }
        }
        ledger.append(
            id = traceId(definition),
            nodes = foundationNodes(definition) + decision + candidates,
            links = listOf(
                DecisionTraceLink(
                    from = planNode(definition).id,
                    to = goalNode(definition).id,
                    type = DecisionTraceLinkType.DERIVED_FROM,
                ),
                DecisionTraceLink(
                    from = decision.id,
                    to = planNode(definition).id,
                    type = DecisionTraceLinkType.DERIVED_FROM,
                ),
            ) + candidateLinks,
        )
    }

    suspend fun recordOwnerPolicy(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        assessment: OwnerPolicyAssessment,
    ): DecisionTraceRecordResult = record(stage = "owner-policy") {
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val policy = DecisionTraceNode.create(
            type = DecisionTraceNodeType.POLICY_CONSTRAINT,
            sourceType = "owner-policy-decision",
            sourceId = assessment.decisionId.value,
            sourceRevision = assessment.policyRevision,
            reasonCodes = if (assessment.allowed) {
                listOf("ALLOWED")
            } else {
                assessment.reasonCodes.map { it.name }
            },
            recordedAt = recordedAt,
        )
        ledger.append(
            id = traceId(goalPhotonId),
            nodes = listOf(goal, policy),
            links = listOf(
                DecisionTraceLink(
                    from = policy.id,
                    to = goal.id,
                    type = DecisionTraceLinkType.CONSTRAINS,
                )
            ),
        )
    }

    suspend fun recordResourceAllocation(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        domain: ResourceBudgetDomain,
        worldSnapshotId: String,
        demandFingerprint: String,
    ): DecisionTraceRecordResult = record(stage = "resource-allocation") {
        require(worldSnapshotId.isNotBlank())
        require(demandFingerprint.isNotBlank())
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val allocation = DecisionTraceNode.create(
            type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
            sourceType = "world-formula-resource-allocation",
            sourceId = worldSnapshotId,
            sourceRevision = 1L,
            reasonCodes = listOf("ALLOCATED", "DOMAIN_${domain.name}", "DEMAND_$demandFingerprint"),
            recordedAt = recordedAt,
        )
        ledger.append(
            id = traceId(goalPhotonId),
            nodes = listOf(goal, allocation),
            links = listOf(
                DecisionTraceLink(allocation.id, goal.id, DecisionTraceLinkType.CONSTRAINS)
            ),
        )
    }

    suspend fun recordResourceBlock(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        source: String,
        reason: String,
    ): DecisionTraceRecordResult = record(stage = "resource-block") {
        require(source.isNotBlank())
        require(reason.isNotBlank())
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val blocked = DecisionTraceNode.create(
            type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
            sourceType = "resource-decision",
            sourceId = source,
            sourceRevision = 1L,
            reasonCodes = listOf(reason),
            recordedAt = recordedAt,
        )
        ledger.append(
            id = traceId(goalPhotonId),
            nodes = listOf(goal, blocked),
            links = listOf(
                DecisionTraceLink(blocked.id, goal.id, DecisionTraceLinkType.CONSTRAINS)
            ),
        )
    }

    suspend fun recordResourceReservation(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        reservation: ResourceBudgetReservation,
    ): DecisionTraceRecordResult = record(stage = "resource-reservation") {
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val reservationNode = resourceReservationNode(reservation)
        ledger.append(
            id = traceId(goalPhotonId),
            nodes = listOf(goal, reservationNode),
            links = listOf(
                DecisionTraceLink(reservationNode.id, goal.id, DecisionTraceLinkType.CONSTRAINS)
            ),
        )
    }

    suspend fun recordOutcome(
        definition: GoalPlanDefinition,
        outcome: Photon,
        succeeded: Boolean,
    ): DecisionTraceRecordResult = record(stage = "execution-outcome") {
        val outcomeNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.EXECUTION_OUTCOME,
            sourceType = "outcome-photon",
            sourceId = outcome.id.value,
            sourceRevision = outcome.revision,
            reasonCodes = listOf(if (succeeded) "SUCCEEDED" else "FAILED"),
            recordedAt = outcome.provenance.createdAt,
        )
        ledger.append(
            id = traceId(definition),
            nodes = foundationNodes(definition) + outcomeNode,
            links = listOf(
                DecisionTraceLink(
                    from = planNode(definition).id,
                    to = goalNode(definition).id,
                    type = DecisionTraceLinkType.DERIVED_FROM,
                ),
                DecisionTraceLink(
                    from = planNode(definition).id,
                    to = outcomeNode.id,
                    type = DecisionTraceLinkType.PRODUCED,
                ),
            ),
        )
    }

    suspend fun snapshot(definition: GoalPlanDefinition): DecisionTraceRecordResult =
        record(stage = "snapshot") {
            ledger.snapshot(traceId(definition))
                ?: error("Decision trace is not yet recorded")
        }

    private fun traceId(definition: GoalPlanDefinition): DecisionTraceId =
        traceId(definition.sourceGoalPhotonId)

    private fun traceId(goalPhotonId: PhotonId): DecisionTraceId =
        DecisionTraceId.create("goal-photon", goalPhotonId.value)

    private fun foundationNodes(definition: GoalPlanDefinition): List<DecisionTraceNode> =
        listOf(goalNode(definition), planNode(definition))

    private fun goalNode(definition: GoalPlanDefinition): DecisionTraceNode =
        goalNode(
            definition.sourceGoalPhotonId,
            definition.sourceGoalPhotonRevision,
            definition.createdAt,
        )

    private fun goalNode(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
    ): DecisionTraceNode = DecisionTraceNode.create(
        type = DecisionTraceNodeType.OBSERVED_FACT,
        sourceType = "goal-photon",
        sourceId = goalPhotonId.value,
        sourceRevision = goalPhotonRevision,
        recordedAt = recordedAt,
    )

    private fun planNode(definition: GoalPlanDefinition): DecisionTraceNode =
        DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "goal-plan",
            sourceId = definition.id.value,
            sourceRevision = definition.planRevision,
            recordedAt = definition.createdAt,
        )

    private fun decisionNode(
        definition: GoalPlanDefinition,
        checkpoint: ConvergenceDecisionCheckpoint,
    ): DecisionTraceNode = DecisionTraceNode.create(
        type = when (checkpoint.decision.state) {
            ConvergenceDecisionState.ACTIONABLE -> DecisionTraceNodeType.SELECTION
            ConvergenceDecisionState.UNRESOLVED,
            ConvergenceDecisionState.CONFLICTED,
            ConvergenceDecisionState.EVIDENCE_REQUIRED,
            ConvergenceDecisionState.CAPABILITY_REQUIRED -> DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY
        },
        sourceType = "convergence-checkpoint",
        sourceId = checkpoint.id.value,
        sourceRevision = 1L,
        reasonCodes = checkpoint.decision.reasons,
        recordedAt = definition.createdAt,
    )

    private fun resourceReservationNode(
        reservation: ResourceBudgetReservation,
    ): DecisionTraceNode = DecisionTraceNode.create(
        type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
        sourceType = "resource-budget-reservation",
        sourceId = reservation.id.value,
        sourceRevision = when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> 1L
            ResourceBudgetReservationState.COMMITTED -> 2L
            ResourceBudgetReservationState.RELEASED -> 3L
        },
        reasonCodes = listOf(reservation.state.name),
        recordedAt = reservation.settledAt ?: reservation.createdAt,
    )

    private suspend fun record(
        stage: String,
        operation: suspend () -> DecisionTrace,
    ): DecisionTraceRecordResult = try {
        DecisionTraceRecordResult.Recorded(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DecisionTraceRecordResult.Unavailable(
            stage = stage,
            reason = error.message ?: error::class.simpleName.orEmpty().ifBlank { "trace-unavailable" },
        )
    }
}

sealed interface DecisionTraceRecordResult {
    data class Recorded(val trace: DecisionTrace) : DecisionTraceRecordResult

    data class Unavailable(
        val stage: String,
        val reason: String,
    ) : DecisionTraceRecordResult {
        init {
            require(stage.isNotBlank())
            require(reason.isNotBlank())
        }
    }
}
