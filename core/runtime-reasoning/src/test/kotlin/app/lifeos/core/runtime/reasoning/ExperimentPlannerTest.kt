package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.EvidenceActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExperimentPlannerTest {
    @Test
    fun `highest information gain is planned first through safe sandbox evidence actions`() {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 3,
                maxTotalResourceCost = 100.0,
            )
        ).plan(
            sourceCycleId = "cycle-1",
            reasoningSearchFingerprint = "search-1",
            counterfactualBatchFingerprint = "counterfactual-1",
            budgetFingerprint = "budget-1",
            requests = listOf(
                request("low", informationGain = 0.25),
                request("high", informationGain = 1.0),
                request("medium", informationGain = 0.5),
            ),
        )

        assertEquals(
            listOf(1.0, 0.5, 0.25),
            plan.items.map { it.proposal.request.expectedInformationGain },
        )
        assertTrue(plan.items.all {
            it.proposal.actionKind == EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT &&
                it.evidenceAction.kind == EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT
        })
        assertTrue(plan.items.all { !it.executionAuthority })
        assertFalse(plan.executionAuthority)
        assertFalse(plan.truncated)
    }

    @Test
    fun `resource budget omits expensive experiments deterministically`() {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 4,
                maxTotalResourceCost = 2.5,
            )
        ).plan(
            sourceCycleId = "cycle-1",
            reasoningSearchFingerprint = "search-1",
            counterfactualBatchFingerprint = "counterfactual-1",
            budgetFingerprint = "budget-1",
            requests = listOf(
                request("high", informationGain = 1.0),
                request("medium", informationGain = 0.5),
                request("low", informationGain = 0.25),
            ),
        )

        assertEquals(1, plan.items.size)
        assertEquals(1.0, plan.items.single().proposal.resourceCost)
        assertEquals(2, plan.omittedRequestFingerprints.size)
        assertTrue(plan.truncated)
        assertEquals(1.0, plan.totalResourceCost)
    }

    @Test
    fun `max experiment count records omitted request identities`() {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 2,
                maxTotalResourceCost = 100.0,
            )
        ).plan(
            sourceCycleId = "cycle-1",
            reasoningSearchFingerprint = "search-1",
            counterfactualBatchFingerprint = "counterfactual-1",
            budgetFingerprint = "budget-1",
            requests = listOf(
                request("a", 1.0),
                request("b", 0.8),
                request("c", 0.6),
            ),
        )

        assertEquals(2, plan.items.size)
        assertEquals(1, plan.omittedRequestFingerprints.size)
        assertTrue(plan.truncated)
    }

    @Test
    fun `request order cannot change canonical plan identity`() {
        val requests = listOf(
            request("a", 1.0),
            request("b", 0.7),
            request("c", 0.4),
        )
        val planner = ExperimentPlanner()

        val first = planner.plan(
            "cycle-1",
            "search-1",
            "counterfactual-1",
            "budget-1",
            requests,
        )
        val second = planner.plan(
            "cycle-1",
            "search-1",
            "counterfactual-1",
            "budget-1",
            requests.reversed(),
        )

        assertEquals(first, second)
    }

    @Test
    fun `reasoning and counterfactual provenance participate in plan identity`() {
        val requests = listOf(request("a", 1.0))
        val planner = ExperimentPlanner()

        val base = planner.plan(
            "cycle-1",
            "search-1",
            "counterfactual-1",
            "budget-1",
            requests,
        )
        val changedSearch = planner.plan(
            "cycle-1",
            "search-2",
            "counterfactual-1",
            "budget-1",
            requests,
        )
        val changedCounterfactual = planner.plan(
            "cycle-1",
            "search-1",
            "counterfactual-2",
            "budget-1",
            requests,
        )

        assertNotEquals(base.fingerprint, changedSearch.fingerprint)
        assertNotEquals(base.fingerprint, changedCounterfactual.fingerprint)
    }

    @Test
    fun `blank provenance and empty discrimination set fail closed`() {
        val planner = ExperimentPlanner()
        val requests = listOf(request("a", 1.0))

        assertFailsWith<IllegalArgumentException> {
            planner.plan("", "search", "counterfactual", "budget", requests)
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan("cycle", "", "counterfactual", "budget", requests)
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan("cycle", "search", "", "budget", requests)
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan("cycle", "search", "counterfactual", "", requests)
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan("cycle", "search", "counterfactual", "budget", emptyList())
        }
    }

    private fun request(
        key: String,
        informationGain: Double,
    ): CausalDiscriminationRequest = CausalDiscriminationRequest(
        candidateIds = setOf("candidate-" + key + "-a", "candidate-" + key + "-b"),
        interventionVariableId = "variable-" + key,
        expectedInformationGain = informationGain,
        rationale = "distinguish-" + key,
    )
}
