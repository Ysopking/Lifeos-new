package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class OutcomeExpectationModelTest {
    private val builder = OutcomeExpectationModelBuilder()

    @Test
    fun `expected outcome band is normalized and ordered`() {
        assertFailsWith<IllegalArgumentException> {
            ExpectedOutcomeBand(lower = -0.1, point = 0.5, upper = 0.9)
        }
        assertFailsWith<IllegalArgumentException> {
            ExpectedOutcomeBand(lower = 0.6, point = 0.5, upper = 0.9)
        }
        assertFailsWith<IllegalArgumentException> {
            ExpectedOutcomeBand(lower = 0.2, point = 0.9, upper = 0.8)
        }

        assertEquals(
            ExpectedOutcomeBand(0.2, 0.5, 0.8),
            ExpectedOutcomeBand(0.2, 0.5, 0.8),
        )
    }

    @Test
    fun `every admitted experiment receives exactly one expectation`() {
        val plan = plan()
        val inputs = plan.items.mapIndexed { index, item ->
            expectation(item.evidenceAction.id, 0.6 + index * 0.1)
        }

        val model = builder.build(plan, inputs)

        assertEquals(plan.items.size, model.entries.size)
        assertEquals(
            plan.items.map { it.evidenceAction.id }.sorted(),
            model.entries.map { it.evidenceActionId },
        )
        assertFalse(model.executionAuthority)
        assertFalse(model.evidenceAuthority)
        assertFalse(model.entries.any { it.executionAuthority || it.evidenceAuthority })
    }

    @Test
    fun `missing unknown and duplicate action expectations fail closed`() {
        val plan = plan()
        val firstId = plan.items.first().evidenceAction.id

        assertFailsWith<IllegalArgumentException> {
            builder.build(
                plan,
                listOf(expectation(firstId, 0.7)),
            )
        }

        val complete = plan.items.map { item ->
            expectation(item.evidenceAction.id, 0.7)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.build(
                plan,
                complete + expectation("unknown-action", 0.7),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            builder.build(
                plan,
                complete + complete.first(),
            )
        }
    }

    @Test
    fun `point signal reuses existing outcome signal vocabulary exactly`() {
        val expected = ExpectedOutcomeSignal(
            completion = ExpectedOutcomeBand(0.7, 0.8, 0.9),
            correctness = ExpectedOutcomeBand(0.5, 0.6, 0.7),
            usefulness = ExpectedOutcomeBand(0.4, 0.5, 0.6),
            policyCompliance = ExpectedOutcomeBand(0.9, 1.0, 1.0),
        )

        val point = expected.pointSignal()

        assertEquals(0.8, point.completion)
        assertEquals(0.6, point.correctness)
        assertEquals(0.5, point.usefulness)
        assertEquals(1.0, point.policyCompliance)
    }

    @Test
    fun `expectation input order cannot change model identity`() {
        val plan = plan()
        val inputs = plan.items.mapIndexed { index, item ->
            expectation(item.evidenceAction.id, 0.55 + index * 0.1)
        }

        val first = builder.build(plan, inputs)
        val second = builder.build(plan, inputs.reversed())

        assertEquals(first, second)
    }

    @Test
    fun `search counterfactual and plan provenance participate in model identity`() {
        val firstPlan = plan(
            searchFingerprint = "search-a",
            counterfactualFingerprint = "counterfactual-a",
        )
        val secondPlan = plan(
            searchFingerprint = "search-b",
            counterfactualFingerprint = "counterfactual-a",
        )
        val thirdPlan = plan(
            searchFingerprint = "search-a",
            counterfactualFingerprint = "counterfactual-b",
        )

        val first = builder.build(
            firstPlan,
            firstPlan.items.map { expectation(it.evidenceAction.id, 0.7) },
        )
        val second = builder.build(
            secondPlan,
            secondPlan.items.map { expectation(it.evidenceAction.id, 0.7) },
        )
        val third = builder.build(
            thirdPlan,
            thirdPlan.items.map { expectation(it.evidenceAction.id, 0.7) },
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.fingerprint, third.fingerprint)
        assertNotEquals(first.experimentPlanFingerprint, second.experimentPlanFingerprint)
        assertNotEquals(first.experimentPlanFingerprint, third.experimentPlanFingerprint)
    }

    @Test
    fun `empty expected signal and empty admitted plan fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            ExpectedOutcomeSignal()
        }

        val emptyPlan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 1,
                maxTotalResourceCost = 0.5,
            )
        ).plan(
            sourceCycleId = "cycle-empty",
            reasoningSearchFingerprint = "search-empty",
            counterfactualBatchFingerprint = "counterfactual-empty",
            budgetFingerprint = "budget-empty",
            requests = listOf(request("too-expensive", 1.0)),
        )
        assertEquals(0, emptyPlan.items.size)

        assertFailsWith<IllegalArgumentException> {
            builder.build(emptyPlan, emptyList())
        }
    }

    private fun plan(
        searchFingerprint: String = "search-1",
        counterfactualFingerprint: String = "counterfactual-1",
    ): ExperimentPlan = ExperimentPlanner(
        ExperimentPlannerConfig(
            maxExperiments = 2,
            maxTotalResourceCost = 10.0,
        )
    ).plan(
        sourceCycleId = "cycle-1",
        reasoningSearchFingerprint = searchFingerprint,
        counterfactualBatchFingerprint = counterfactualFingerprint,
        budgetFingerprint = "budget-1",
        requests = listOf(
            request("a", 1.0),
            request("b", 0.5),
        ),
    )

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

    private fun expectation(
        evidenceActionId: String,
        point: Double,
    ): OutcomeExpectationInput = OutcomeExpectationInput(
        evidenceActionId = evidenceActionId,
        expected = ExpectedOutcomeSignal(
            completion = ExpectedOutcomeBand(
                lower = (point - 0.1).coerceAtLeast(0.0),
                point = point,
                upper = (point + 0.1).coerceAtMost(1.0),
            ),
            correctness = ExpectedOutcomeBand(
                lower = (point - 0.2).coerceAtLeast(0.0),
                point = point,
                upper = (point + 0.2).coerceAtMost(1.0),
            ),
        ),
        confidence = 0.8,
        rationale = "Expected bounded experiment outcome",
    )
}
