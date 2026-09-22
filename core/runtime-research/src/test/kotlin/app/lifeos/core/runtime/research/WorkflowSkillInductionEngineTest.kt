package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
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
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEvaluationMode
import app.lifeos.core.runtime.policy.OwnerPolicyGrantId
import app.lifeos.core.runtime.reasoning.LearningEpisode
import app.lifeos.core.runtime.reasoning.LearningEpisodeId
import app.lifeos.core.runtime.reasoning.LearningEpisodeStatus
import app.lifeos.core.runtime.reasoning.LearningEpisodeSummary
import app.lifeos.core.runtime.reasoning.ProceduralSkillTrace
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowSkillInductionEngineTest {
    @Test
    fun repeated_positive_workflows_reuse_b376_induction_without_activation_authority() {
        val first = support("cycle-a", 'a')
        val second = support("cycle-b", 'b')
        val shape = first.proceduralTrace.shapeFingerprint

        val report = WorkflowSkillInductionEngine().induce(
            supports = listOf(second, first),
            semanticKeysByShape = mapOf(shape to "skill:calendar-sync"),
        )

        val candidate = report.candidates.single()
        assertEquals("skill:calendar-sync", candidate.semanticKey)
        assertEquals(listOf("cycle-a", "cycle-b"), candidate.supportingCycleIds)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.activationAllowed)
        assertFalse(candidate.promotionAllowed)
        assertFalse(report.executionAuthority)
        assertFalse(report.activationAuthority)
        assertFalse(report.promotionAuthority)
    }

    @Test
    fun negative_outcome_cannot_be_bound_as_workflow_skill_support() {
        val trace = trace("cycle-a")
        val negative = outcomeCandidate('c', ExternalActionOutcomeState.CONTRADICTED)

        assertFailsWith<IllegalArgumentException> {
            WorkflowSkillSupport.bind(trace, negative)
        }
    }

    @Test
    fun one_action_outcome_cannot_be_counted_twice() {
        val outcome = outcomeCandidate('d', ExternalActionOutcomeState.CONFIRMED)
        val first = WorkflowSkillSupport.bind(trace("cycle-a"), outcome)
        val second = WorkflowSkillSupport.bind(trace("cycle-b"), outcome)

        assertFailsWith<IllegalArgumentException> {
            WorkflowSkillInductionEngine().induce(listOf(first, second))
        }
    }

    @Test
    fun support_input_order_does_not_change_report() {
        val a = support("cycle-a", 'e')
        val b = support("cycle-b", 'f')
        val engine = WorkflowSkillInductionEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a)),
        )
    }

    private fun support(cycle: String, seed: Char): WorkflowSkillSupport =
        WorkflowSkillSupport.bind(
            proceduralTrace = trace(cycle),
            outcomeLearningCandidate = outcomeCandidate(seed, ExternalActionOutcomeState.CONFIRMED),
        )

    private fun outcomeCandidate(
        seed: Char,
        state: ExternalActionOutcomeState,
    ): ActionOutcomeLearningCandidate =
        ActionOutcomeLearningEngine()
            .evaluate(actionGraph(seed, state))
            .candidates
            .single()

    private fun actionGraph(
        seed: Char,
        state: ExternalActionOutcomeState,
    ): ExternalActionReceiptGraph {
        val requestFp = seed.toString().repeat(64)
        val initial = ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = requestFp,
            resourceIdentity = "https://api.example.com/" + seed,
            dispatchPlanFingerprint = seed.lowercaseChar().toString().repeat(64),
            policyAssessment = OwnerPolicyAssessment(
                decisionId = OwnerPolicyDecisionId(
                    OwnerPolicyDecisionId.PREFIX + "d".repeat(64)
                ),
                policyRevision = 1L,
                mode = OwnerPolicyEvaluationMode.LIVE,
                requestFingerprint = "e".repeat(64),
                allowed = true,
                grantId = OwnerPolicyGrantId(
                    OwnerPolicyGrantId.PREFIX + "c".repeat(64)
                ),
            ),
            receipt = EffectReceipt(
                actionId = "action-" + seed,
                idempotencyKey = "idem-" + seed,
                state = ExternalEffectState.UNKNOWN_OUTCOME,
                recordedAt = NOW,
                externalReference = "ref-" + seed,
            ),
        )
        val receipt = initial.nodesAdded.filterIsInstance<ExternalEffectReceiptNode>().single()
        val observation = ExternalObservationNode(
            observationFingerprint = seed.lowercaseChar().toString().repeat(64),
            resourceIdentity = "https://api.example.com/" + seed,
            observedAt = NOW.plusSeconds(1),
            observationRevision = "rev-" + seed,
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

    private fun trace(cycle: String): ProceduralSkillTrace {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-" + cycle),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec("read", "Read current state.", priority = 10),
                GoalStepSpec(
                    "act",
                    "Perform bounded action.",
                    dependencyKeys = setOf("read"),
                    priority = 5,
                ),
            ),
            createdAt = NOW,
        )
        val transitions = plan.steps.mapIndexed { index, step ->
            GoalPlanTransition.create(
                planId = plan.id,
                predecessorId = null,
                stepId = step.id,
                fromState = GoalStepState.RUNNING,
                toState = GoalStepState.COMPLETED,
                reason = "verified completion",
                sourceFingerprint = StableFieldIds.fingerprint("source", cycle, step.key),
                actionId = "action-" + cycle + "-" + step.key,
                actionIdempotencyKey = "idem-" + cycle + "-" + step.key,
                outcomePhotonId = PhotonId("outcome-" + cycle + "-" + step.key),
                createdAt = NOW.plusSeconds(index.toLong()),
            )
        }
        return ProceduralSkillTrace.from(
            episode = learningEpisode(cycle),
            plan = plan,
            transitions = transitions,
        )
    }

    private fun learningEpisode(cycle: String): LearningEpisode {
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", cycle)
        )
        val summary = LearningEpisodeSummary(
            expectedActions = 1,
            missingObservations = 0,
            incompleteObservations = 0,
            unverifiedObservations = 0,
            withinExpectedBand = 1,
            outsideExpectedBand = 0,
            causalAssignments = 0,
        )
        val parts = listOf(
            StableFieldIds.fingerprint("hypothesis", cycle),
            StableFieldIds.fingerprint("search", cycle),
            StableFieldIds.fingerprint("counterfactual", cycle),
            StableFieldIds.fingerprint("plan", cycle),
            StableFieldIds.fingerprint("expectation", cycle),
            StableFieldIds.fingerprint("error", cycle),
        )
        val fp = StableFieldIds.fingerprint(
            "learning-episode/v1",
            cycle,
            "1",
            "",
            problemId.value,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            parts[5],
            "",
            LearningEpisodeStatus.VERIFIED_OUTCOME.name,
            summary.fingerprint(),
            NOW.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fp),
            sourceCycleId = cycle,
            cycleRevision = 1L,
            predecessorId = null,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = parts[0],
            reasoningSearchFingerprint = parts[1],
            counterfactualBatchFingerprint = parts[2],
            experimentPlanFingerprint = parts[3],
            expectationModelFingerprint = parts[4],
            predictionErrorReportFingerprint = parts[5],
            causalCreditReportFingerprint = null,
            status = LearningEpisodeStatus.VERIFIED_OUTCOME,
            summary = summary,
            createdAt = NOW,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    }
}
