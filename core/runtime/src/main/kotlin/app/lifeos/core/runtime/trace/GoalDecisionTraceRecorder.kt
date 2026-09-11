package app.lifeos.core.runtime.trace

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import kotlinx.coroutines.CancellationException

/**
 * Non-authoritative V15 recorder over already-durable V7/V5/outcome evidence.
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
        DecisionTraceId.create("goal-photon", definition.sourceGoalPhotonId.value)

    private fun foundationNodes(definition: GoalPlanDefinition): List<DecisionTraceNode> =
        listOf(goalNode(definition), planNode(definition))

    private fun goalNode(definition: GoalPlanDefinition): DecisionTraceNode =
        DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "goal-photon",
            sourceId = definition.sourceGoalPhotonId.value,
            sourceRevision = definition.sourceGoalPhotonRevision,
            recordedAt = definition.createdAt,
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
