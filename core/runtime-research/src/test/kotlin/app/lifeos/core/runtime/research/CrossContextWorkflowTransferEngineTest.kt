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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CrossContextWorkflowTransferEngineTest {
    @Test
    fun exact_structure_can_cross_web_site_without_semantic_or_activation_authority() {
        val report = workflowReport()
        val candidate = report.candidates.single()
        val source = context(WorkflowExecutionContextKind.WEB_SITE, '1')
        val target = context(WorkflowExecutionContextKind.WEB_SITE, '2')
        val evidence = TargetWorkflowStructureEvidence.create(
            context = target,
            structure = WorkflowStructureSignature.from(candidate),
            evidenceFingerprint = "a".repeat(64),
        )

        val hypothesis = assertNotNull(
            CrossContextWorkflowTransferEngine().propose(report, candidate, source, evidence)
        )

        assertFalse(hypothesis.semanticIdentityEstablished)
        assertFalse(hypothesis.directActivationAllowed)
        assertFalse(hypothesis.executionAuthority)
        assertFalse(hypothesis.promotionAuthority)
        assertFalse(hypothesis.ownerPolicyAuthority)
    }

    @Test
    fun exact_structure_can_cross_web_to_android_only_as_hypothesis() {
        val report = workflowReport()
        val candidate = report.candidates.single()
        val evidence = TargetWorkflowStructureEvidence.create(
            context = context(WorkflowExecutionContextKind.ANDROID_APP, '3'),
            structure = WorkflowStructureSignature.from(candidate),
            evidenceFingerprint = "b".repeat(64),
        )

        val hypothesis = CrossContextWorkflowTransferEngine().propose(
            report,
            candidate,
            context(WorkflowExecutionContextKind.WEB_SITE, '4'),
            evidence,
        )

        assertNotNull(hypothesis)
        assertFalse(hypothesis.executionAuthority)
    }

    @Test
    fun mismatched_target_structure_does_not_transfer() {
        val report = workflowReport()
        val candidate = report.candidates.single()
        val structure = WorkflowStructureSignature.from(candidate)
        val mismatched = WorkflowStructureSignature(
            topologyFingerprint = structure.topologyFingerprint,
            relationFingerprint = "c".repeat(64),
            dimensionFingerprint = structure.dimensionFingerprint,
            fingerprint = fingerprint(
                "workflow-structure-signature/v1",
                structure.topologyFingerprint,
                "c".repeat(64),
                structure.dimensionFingerprint,
            ),
        )
        val evidence = TargetWorkflowStructureEvidence.create(
            context = context(WorkflowExecutionContextKind.ANDROID_APP, '5'),
            structure = mismatched,
            evidenceFingerprint = "d".repeat(64),
        )

        assertNull(
            CrossContextWorkflowTransferEngine().propose(
                report,
                candidate,
                context(WorkflowExecutionContextKind.WEB_SITE, '6'),
                evidence,
            )
        )
    }

    @Test
    fun same_context_is_rejected() {
        val report = workflowReport()
        val candidate = report.candidates.single()
        val context = context(WorkflowExecutionContextKind.WEB_SITE, '7')
        val evidence = TargetWorkflowStructureEvidence.create(
            context = context,
            structure = WorkflowStructureSignature.from(candidate),
            evidenceFingerprint = "e".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            CrossContextWorkflowTransferEngine().propose(report, candidate, context, evidence)
        }
    }

    private fun workflowReport(): WorkflowSkillInductionReport {
        val a = WorkflowSkillSupport.bind(trace("cycle-a"), outcomeCandidate('a'))
        val b = WorkflowSkillSupport.bind(trace("cycle-b"), outcomeCandidate('b'))
        return WorkflowSkillInductionEngine().induce(listOf(a, b))
    }

    private fun context(kind: WorkflowExecutionContextKind, seed: Char): WorkflowExecutionContext =
        WorkflowExecutionContext.create(
            kind = kind,
            identityFingerprint = seed.toString().repeat(64),
            contractFingerprint = seed.uppercaseChar().toString().lowercase().repeat(64),
        )

    private fun outcomeCandidate(seed: Char): ActionOutcomeLearningCandidate =
        ActionOutcomeLearningEngine()
            .evaluate(actionGraph(seed, ExternalActionOutcomeState.CONFIRMED))
            .candidates
            .single()

    private fun actionGraph(
        seed: Char,
        state: ExternalActionOutcomeState,
    ): ExternalActionReceiptGraph {
        val initial = ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = seed.toString().repeat(64),
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

    private fun fingerprint(domain: String, vararg parts: String): String =
        StableFieldIds.fingerprint(domain, *parts)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    }
}
