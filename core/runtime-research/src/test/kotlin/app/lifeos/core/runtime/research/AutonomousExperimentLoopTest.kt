package app.lifeos.core.runtime.research

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.CausalEvidenceKind
import app.lifeos.core.runtime.level7.CausalObservation
import app.lifeos.core.runtime.reasoning.CausalCreditObservationInput
import app.lifeos.core.runtime.reasoning.CausalCreditRelation
import app.lifeos.core.runtime.reasoning.ExperimentPlanner
import app.lifeos.core.runtime.reasoning.ExpectedOutcomeBand
import app.lifeos.core.runtime.reasoning.ExpectedOutcomeSignal
import app.lifeos.core.runtime.reasoning.ObservedOutcomeInput
import app.lifeos.core.runtime.reasoning.OutcomeExpectationInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomousExperimentLoopTest {
    @Test
    fun prepare_reuses_b370_b371_and_grants_no_execution_authority() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))

        assertEquals(plan.fingerprint, model.experimentPlanFingerprint)
        assertFalse(plan.executionAuthority)
        assertFalse(model.executionAuthority)
        assertFalse(model.evidenceAuthority)
    }

    @Test
    fun missing_observation_stays_awaiting_and_emits_no_learning_candidate() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))

        val assessment = loop.assess(plan, model, emptyList())

        assertEquals(
            AutonomousExperimentState.AWAITING_VERIFIED_OBSERVATION,
            assessment.cycle.state,
        )
        assertTrue(assessment.learningCandidates.isEmpty())
        assertFalse(assessment.cycle.executionAuthority)
        assertFalse(assessment.cycle.externalEffectAuthority)
        assertFalse(assessment.cycle.causalAuthority)
    }

    @Test
    fun unverified_observation_never_becomes_learning_candidate() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))
        val item = plan.items.single()
        val observation = observation(item.evidenceAction.id, verified = false, correctness = 0.9)

        val assessment = loop.assess(plan, model, listOf(observation))

        assertEquals(
            AutonomousExperimentState.AWAITING_VERIFIED_OBSERVATION,
            assessment.cycle.state,
        )
        assertTrue(assessment.learningCandidates.isEmpty())
        assertEquals(listOf(item.evidenceAction.id), assessment.cycle.unverifiedObservationActionIds)
    }

    @Test
    fun verified_outcome_reenters_only_as_next_cycle_learning_candidate() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))
        val item = plan.items.single()
        val observation = observation(item.evidenceAction.id, verified = true, correctness = 0.95)

        val assessment = loop.assess(plan, model, listOf(observation))
        val candidate = assessment.learningCandidates.single()

        assertEquals(
            AutonomousExperimentState.VERIFIED_WITHIN_EXPECTATION,
            assessment.cycle.state,
        )
        assertTrue(candidate.entersNextCycleOnly)
        assertFalse(candidate.truthAuthority)
        assertFalse(candidate.causalAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.worldMutationAuthority)
        assertFalse(candidate.promotionAuthority)
    }

    @Test
    fun verified_prediction_error_is_explicit_and_still_not_causal_authority() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))
        val item = plan.items.single()
        val observation = observation(item.evidenceAction.id, verified = true, correctness = 0.1)

        val assessment = loop.assess(plan, model, listOf(observation))

        assertEquals(
            AutonomousExperimentState.VERIFIED_OUTSIDE_EXPECTATION,
            assessment.cycle.state,
        )
        assertEquals(1, assessment.learningCandidates.size)
        assertFalse(assessment.learningCandidates.single().causalAuthority)
        assertFalse(assessment.predictionErrors.causalAuthority)
    }

    @Test
    fun causal_credit_request_fails_closed_until_all_observations_are_verified() {
        val plan = plan()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(plan, expectations(plan))
        val item = plan.items.single()
        val observation = observation(item.evidenceAction.id, verified = false, correctness = 0.9)

        assertFailsWith<IllegalArgumentException> {
            loop.assess(
                plan = plan,
                expectationModel = model,
                observations = listOf(observation),
                causalObservations = listOf(causalObservation(item)),
            )
        }
    }

    @Test
    fun substituted_expectation_model_is_rejected() {
        val firstPlan = plan("one")
        val secondPlan = plan("two")
        val loop = AutonomousExperimentLoop()
        val secondModel = loop.prepare(secondPlan, expectations(secondPlan))

        assertFailsWith<IllegalArgumentException> {
            loop.assess(firstPlan, secondModel, emptyList())
        }
    }

    private fun causalObservation(
        item: app.lifeos.core.runtime.reasoning.ExperimentPlanItem,
    ): CausalCreditObservationInput {
        val request = item.proposal.request
        return CausalCreditObservationInput(
            evidenceActionId = item.evidenceAction.id,
            observation = CausalObservation(
                id = "causal-observation-b389",
                sourceSnapshotId = "snapshot-before",
                targetSnapshotId = "snapshot-after",
                sourceTarget = WorldTargetRef(WorldNodeKind.EXPERIMENT, "experiment-source"),
                targetTarget = WorldTargetRef(WorldNodeKind.OUTCOME, "experiment-outcome"),
                sourceDimension = WorldSignalDimension.CAUSAL_SUPPORT,
                targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
                signedEffect = 0.5,
                confidence = 0.9,
                evidenceKind = CausalEvidenceKind.CONTROLLED_INTERVENTION,
                provenanceFingerprint = "unverified-provenance",
            ),
            candidateRelations = request.candidateIds.associateWith {
                CausalCreditRelation.UNRESOLVED
            },
        )
    }

    private fun plan(
        suffix: String = "default",
    ) = ExperimentPlanner().plan(
        sourceCycleId = "cycle-b389-$suffix",
        reasoningSearchFingerprint = "reasoning-$suffix",
        counterfactualBatchFingerprint = "counterfactual-$suffix",
        budgetFingerprint = "budget-$suffix",
        requests = listOf(
            CausalDiscriminationRequest(
                candidateIds = setOf("candidate-a-$suffix", "candidate-b-$suffix"),
                interventionVariableId = "variable-$suffix",
                expectedInformationGain = 1.0,
                rationale = "distinguish-$suffix",
            )
        ),
    )

    private fun expectations(
        plan: app.lifeos.core.runtime.reasoning.ExperimentPlan,
    ): List<OutcomeExpectationInput> =
        plan.items.map { item ->
            OutcomeExpectationInput(
                evidenceActionId = item.evidenceAction.id,
                expected = ExpectedOutcomeSignal(
                    correctness = ExpectedOutcomeBand(
                        lower = 0.8,
                        point = 0.9,
                        upper = 1.0,
                    ),
                ),
                confidence = 0.9,
                rationale = "expected-correctness",
            )
        }

    private fun observation(
        actionId: String,
        verified: Boolean,
        correctness: Double,
    ): ObservedOutcomeInput =
        ObservedOutcomeInput(
            evidenceActionId = actionId,
            observationRef = "photon-revision:b389",
            observationFingerprint =
                if (verified) "verified-observation" else "unverified-observation",
            signal = OutcomeSignal(correctness = correctness),
            confidence = 0.9,
            verified = verified,
        )
}
