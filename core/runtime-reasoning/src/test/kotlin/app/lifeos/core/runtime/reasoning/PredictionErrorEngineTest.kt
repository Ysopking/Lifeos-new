package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionErrorEngineTest {
    private val engine = PredictionErrorEngine()

    @Test
    fun `verified values inside and outside bands produce explicit states`() {
        val model = model()
        val first = model.entries[0]
        val second = model.entries[1]
        val report = engine.compare(
            model,
            listOf(
                observed(
                    first.evidenceActionId,
                    completion = 0.75,
                    correctness = 0.70,
                    verified = true,
                ),
                observed(
                    second.evidenceActionId,
                    completion = 0.20,
                    correctness = 0.25,
                    verified = true,
                ),
            ),
        )

        val byId = report.entries.associateBy { it.evidenceActionId }
        assertEquals(
            PredictionErrorState.WITHIN_EXPECTED_BAND,
            byId.getValue(first.evidenceActionId).state,
        )
        assertEquals(
            PredictionErrorState.OUTSIDE_EXPECTED_BAND,
            byId.getValue(second.evidenceActionId).state,
        )
        assertEquals(0.0, byId.getValue(first.evidenceActionId).maxBandDeviation)
        assertTrue(requireNotNull(byId.getValue(second.evidenceActionId).maxBandDeviation) > 0.0)
        assertTrue(report.complete)
        assertTrue(report.verifiedComplete)
    }

    @Test
    fun `missing observations stay explicit and cannot become learning eligible`() {
        val model = model()
        val first = model.entries.first()
        val report = engine.compare(
            model,
            listOf(
                observed(
                    first.evidenceActionId,
                    completion = 0.75,
                    correctness = 0.70,
                    verified = true,
                )
            ),
        )

        assertEquals(1, report.missingObservationActionIds.size)
        assertFalse(report.complete)
        val missing = report.entries.single {
            it.state == PredictionErrorState.MISSING_OBSERVATION
        }
        assertFalse(missing.learningEligible)
        assertFalse(missing.causalAuthority)
        assertTrue(missing.components.all { it.actual == null })
    }

    @Test
    fun `missing expected dimensions make observation incomplete`() {
        val model = model()
        val observations = model.entries.map { entry ->
            ObservedOutcomeInput(
                evidenceActionId = entry.evidenceActionId,
                observationRef = "obs:" + entry.evidenceActionId,
                observationFingerprint = "fingerprint:" + entry.evidenceActionId,
                signal = OutcomeSignal(completion = 0.75),
                confidence = 0.9,
                verified = true,
            )
        }

        val report = engine.compare(model, observations)

        assertEquals(model.entries.size, report.incompleteObservationActionIds.size)
        assertFalse(report.complete)
        assertTrue(report.entries.all {
            it.state == PredictionErrorState.INCOMPLETE_OBSERVATION
        })
        assertTrue(report.entries.all { !it.learningEligible })
    }

    @Test
    fun `unverified complete observations compute error but remain ineligible`() {
        val model = model()
        val observations = model.entries.map { entry ->
            observed(
                entry.evidenceActionId,
                completion = 0.75,
                correctness = 0.70,
                verified = false,
            )
        }

        val report = engine.compare(model, observations)

        assertTrue(report.complete)
        assertFalse(report.verifiedComplete)
        assertEquals(model.entries.size, report.unverifiedObservationActionIds.size)
        assertTrue(report.entries.all {
            it.state == PredictionErrorState.UNVERIFIED_OBSERVATION
        })
        assertTrue(report.entries.all { !it.learningEligible })
        assertTrue(report.entries.all { it.meanAbsolutePointError != null })
    }

    @Test
    fun `prediction error arithmetic is signed against point and zero inside band`() {
        val component = PredictionErrorComponent(
            dimension = PredictionErrorDimension.COMPLETION,
            expected = ExpectedOutcomeBand(0.6, 0.7, 0.8),
            actual = 0.75,
            signedPointError = 0.05,
            bandDeviation = 0.0,
        )
        assertEquals(0.05, component.signedPointError)
        assertEquals(0.0, component.bandDeviation)

        assertFailsWith<IllegalArgumentException> {
            PredictionErrorComponent(
                dimension = PredictionErrorDimension.COMPLETION,
                expected = ExpectedOutcomeBand(0.6, 0.7, 0.8),
                actual = 0.9,
                signedPointError = 0.2,
                bandDeviation = 0.0,
            )
        }
    }

    @Test
    fun `observation order cannot change report identity`() {
        val model = model()
        val observations = model.entries.mapIndexed { index, entry ->
            observed(
                entry.evidenceActionId,
                completion = 0.70 + index * 0.05,
                correctness = 0.65 + index * 0.05,
                verified = true,
            )
        }

        val first = engine.compare(model, observations)
        val second = engine.compare(model, observations.reversed())

        assertEquals(first, second)
    }

    @Test
    fun `unknown and duplicate observations fail closed`() {
        val model = model()
        val first = model.entries.first()
        val valid = observed(
            first.evidenceActionId,
            completion = 0.75,
            correctness = 0.70,
            verified = true,
        )

        assertFailsWith<IllegalArgumentException> {
            engine.compare(
                model,
                listOf(
                    valid,
                    observed(
                        "unknown-action",
                        completion = 0.75,
                        correctness = 0.70,
                        verified = true,
                    ),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            engine.compare(model, listOf(valid, valid))
        }
    }

    @Test
    fun `report and entries never carry causal authority`() {
        val model = model()
        val observations = model.entries.map { entry ->
            observed(
                entry.evidenceActionId,
                completion = 0.75,
                correctness = 0.70,
                verified = true,
            )
        }

        val report = engine.compare(model, observations)

        assertFalse(report.causalAuthority)
        assertTrue(report.entries.all { !it.causalAuthority })
    }

    private fun model(): OutcomeExpectationModel {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 2,
                maxTotalResourceCost = 10.0,
            )
        ).plan(
            sourceCycleId = "cycle-b372",
            reasoningSearchFingerprint = "search-b372",
            counterfactualBatchFingerprint = "counterfactual-b372",
            budgetFingerprint = "budget-b372",
            requests = listOf(
                request("a", 1.0),
                request("b", 0.5),
            ),
        )
        return OutcomeExpectationModelBuilder().build(
            plan,
            plan.items.mapIndexed { index, item ->
                val point = 0.70 + index * 0.05
                OutcomeExpectationInput(
                    evidenceActionId = item.evidenceAction.id,
                    expected = ExpectedOutcomeSignal(
                        completion = ExpectedOutcomeBand(
                            lower = point - 0.10,
                            point = point,
                            upper = point + 0.10,
                        ),
                        correctness = ExpectedOutcomeBand(
                            lower = point - 0.15,
                            point = point,
                            upper = point + 0.15,
                        ),
                    ),
                    confidence = 0.8,
                    rationale = "B372 expectation fixture",
                )
            },
        )
    }

    private fun request(
        key: String,
        informationGain: Double,
    ): CausalDiscriminationRequest = CausalDiscriminationRequest(
        candidateIds = setOf(
            "candidate-" + key + "-a",
            "candidate-" + key + "-b",
        ),
        interventionVariableId = "variable-" + key,
        expectedInformationGain = informationGain,
        rationale = "distinguish-" + key,
    )

    private fun observed(
        actionId: String,
        completion: Double,
        correctness: Double,
        verified: Boolean,
    ): ObservedOutcomeInput = ObservedOutcomeInput(
        evidenceActionId = actionId,
        observationRef = "observation:" + actionId,
        observationFingerprint = "observation-fingerprint:" + actionId,
        signal = OutcomeSignal(
            completion = completion,
            correctness = correctness,
        ),
        confidence = 0.9,
        verified = verified,
    )
}
