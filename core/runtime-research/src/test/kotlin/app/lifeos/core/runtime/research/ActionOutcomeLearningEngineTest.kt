package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.agency.EffectReceipt
import app.lifeos.core.runtime.agency.ExternalActionEdgeType
import app.lifeos.core.runtime.agency.ExternalActionGraphEdge
import app.lifeos.core.runtime.agency.ExternalActionGraphRevision
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.agency.ExternalActionReceiptGraph
import app.lifeos.core.runtime.agency.ExternalEffectReceiptNode
import app.lifeos.core.runtime.agency.ExternalEffectState
import app.lifeos.core.runtime.agency.ExternalObservationNode
import app.lifeos.core.runtime.agency.OutcomeNode
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEvaluationMode
import app.lifeos.core.runtime.policy.OwnerPolicyGrantId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionOutcomeLearningEngineTest {
    @Test
    fun confirmed_outcome_becomes_positive_next_cycle_candidate_without_causal_authority() {
        val graph = graph(ExternalActionOutcomeState.CONFIRMED)
        val report = ActionOutcomeLearningEngine().evaluate(graph)

        assertEquals(ActionOutcomeLearningState.CONFIRMED_EVIDENCE, report.evidence.state)
        assertEquals(ActionOutcomeLearningDirection.POSITIVE, report.candidates.single().direction)
        assertTrue(report.candidates.single().nextCycleEligible)
        assertFalse(report.evidence.causalAuthority)
        assertFalse(report.candidates.single().causalAuthority)
        assertFalse(report.currentCycleWorldMutationAllowed)
        assertFalse(report.automaticPromotionAllowed)
    }

    @Test
    fun contradicted_outcome_becomes_negative_candidate() {
        val report = ActionOutcomeLearningEngine().evaluate(
            graph(ExternalActionOutcomeState.CONTRADICTED)
        )

        assertEquals(ActionOutcomeLearningState.CONTRADICTED_EVIDENCE, report.evidence.state)
        assertEquals(ActionOutcomeLearningDirection.NEGATIVE, report.candidates.single().direction)
    }

    @Test
    fun partial_and_unknown_outcomes_do_not_become_learning_candidates() {
        for (state in listOf(
            ExternalActionOutcomeState.PARTIAL,
            ExternalActionOutcomeState.UNKNOWN,
        )) {
            val report = ActionOutcomeLearningEngine().evaluate(graph(state))
            assertTrue(report.candidates.isEmpty())
        }
    }

    @Test
    fun missing_outcome_stays_unresolved() {
        val initial = initial()
        val graph = ExternalActionReceiptGraph.replay(listOf(initial))

        val report = ActionOutcomeLearningEngine().evaluate(graph)

        assertEquals(ActionOutcomeLearningState.UNRESOLVED, report.evidence.state)
        assertTrue(report.candidates.isEmpty())
    }

    private fun graph(state: ExternalActionOutcomeState): ExternalActionReceiptGraph {
        val initial = initial()
        val receipt = initial.nodesAdded.filterIsInstance<ExternalEffectReceiptNode>().single()
        val observation = ExternalObservationNode(
            observationFingerprint = "1".repeat(64),
            resourceIdentity = RESOURCE,
            observedAt = NOW.plusSeconds(30),
            observationRevision = "rev-1",
            fieldFingerprints = mapOf("state" to "2".repeat(64)),
        )
        val second = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 2L,
            predecessorRevisionId = initial.revisionId,
            nodesAdded = listOf(observation),
            edgesAdded = listOf(
                ExternalActionGraphEdge(
                    ExternalActionEdgeType.RECEIPT_OBSERVED_BY,
                    receipt.id,
                    observation.id,
                )
            ),
        )
        val outcome = OutcomeNode(
            state = state,
            basisObservationId = observation.id,
            reasonCode = "verified-observation",
        )
        val third = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 3L,
            predecessorRevisionId = second.revisionId,
            nodesAdded = listOf(outcome),
            edgesAdded = listOf(
                ExternalActionGraphEdge(
                    ExternalActionEdgeType.OBSERVATION_CLASSIFIED_AS_OUTCOME,
                    observation.id,
                    outcome.id,
                )
            ),
        )
        return ExternalActionReceiptGraph.replay(listOf(initial, second, third))
    }

    private fun initial(): ExternalActionGraphRevision =
        ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = "a".repeat(64),
            resourceIdentity = RESOURCE,
            dispatchPlanFingerprint = "b".repeat(64),
            policyAssessment = OwnerPolicyAssessment(
                decisionId = OwnerPolicyDecisionId(
                    OwnerPolicyDecisionId.PREFIX + "d".repeat(64)
                ),
                policyRevision = 7L,
                mode = OwnerPolicyEvaluationMode.LIVE,
                requestFingerprint = "e".repeat(64),
                allowed = true,
                grantId = OwnerPolicyGrantId(
                    OwnerPolicyGrantId.PREFIX + "c".repeat(64)
                ),
            ),
            receipt = EffectReceipt(
                actionId = "action-1",
                idempotencyKey = "idem-1",
                state = ExternalEffectState.UNKNOWN_OUTCOME,
                recordedAt = NOW,
                externalReference = "external-ref",
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
        const val RESOURCE = "android-file://shared-primary/Documents/a.txt"
    }
}
