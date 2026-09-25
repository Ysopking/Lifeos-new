package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.EvidenceActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InformationActionPlannerTest {
    private val planner = InformationActionPlanner(
        InformationActionPolicy(maxActions = 2)
    )

    @Test
    fun `planner prefers information gain after bounded cost penalties`() {
        val plan = planner.plan(
            sourceCycleId = "cycle:1",
            budgetFingerprint = "budget:1",
            identifiability = unresolved(),
            allowedKinds = setOf(
                EvidenceActionKind.ASK_USER,
                EvidenceActionKind.DEEP_SEARCH,
            ),
            candidates = listOf(
                candidate(
                    id = "ask",
                    kind = EvidenceActionKind.ASK_USER,
                    gain = 700_000L,
                    privacy = 100_000L,
                ),
                candidate(
                    id = "search",
                    kind = EvidenceActionKind.DEEP_SEARCH,
                    gain = 850_000L,
                    resource = 300_000L,
                    privacy = 0L,
                ),
            ),
        )

        assertEquals(2, plan.items.size)
        assertEquals("search", plan.items.first().candidate.interventionId)
        assertTrue(plan.items.first().scoreMicros > plan.items.last().scoreMicros)
    }

    @Test
    fun `disallowed action kinds are omitted rather than widened by planner`() {
        val disallowed = candidate(
            id = "experiment",
            kind = EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
            gain = 1_000_000L,
        )
        val allowed = candidate(
            id = "ask",
            kind = EvidenceActionKind.ASK_USER,
            gain = 300_000L,
        )

        val plan = planner.plan(
            sourceCycleId = "cycle:1",
            budgetFingerprint = "budget:1",
            identifiability = unresolved(),
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(disallowed, allowed),
        )

        assertEquals(listOf("ask"), plan.items.map { it.candidate.interventionId })
        assertTrue(disallowed.fingerprint in plan.omittedCandidateFingerprints)
    }

    @Test
    fun `max action bound truncates lower-ranked candidates deterministically`() {
        val candidates = listOf(
            candidate("a", EvidenceActionKind.ASK_USER, 900_000L),
            candidate("b", EvidenceActionKind.MEMORY_LOOKUP, 800_000L),
            candidate("c", EvidenceActionKind.SOURCE_REFRESH, 700_000L),
        )

        val plan = planner.plan(
            sourceCycleId = "cycle:1",
            budgetFingerprint = "budget:1",
            identifiability = unresolved(),
            allowedKinds = candidates.map { it.kind }.toSet(),
            candidates = candidates.reversed(),
        )

        assertEquals(listOf("a", "b"), plan.items.map { it.candidate.interventionId })
        assertEquals(1, plan.omittedCandidateFingerprints.size)
    }

    @Test
    fun `all planned evidence requests remain non executing`() {
        val plan = planner.plan(
            sourceCycleId = "cycle:1",
            budgetFingerprint = "budget:1",
            identifiability = unresolved(),
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(
                candidate("ask", EvidenceActionKind.ASK_USER, 500_000L)
            ),
        )

        assertFalse(plan.executionAuthority)
        assertFalse(plan.items.single().executionAuthority)
        assertFalse(plan.items.single().request.executionAuthority)
        assertFalse(plan.items.single().request.currentCycleWorldMutationAllowed)
    }

    @Test
    fun `observationally distinct candidates do not enter information-gap planner`() {
        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                sourceCycleId = "cycle:1",
                budgetFingerprint = "budget:1",
                identifiability = IdentifiabilityAssessment.create(
                    realizationProfileFingerprint = "profile",
                    candidateIds = listOf("a", "b"),
                    status = IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT,
                    sharedInterventionIds = emptyList(),
                    discriminatingInterventionIds = emptyList(),
                ),
                allowedKinds = setOf(EvidenceActionKind.ASK_USER),
                candidates = listOf(
                    candidate("ask", EvidenceActionKind.ASK_USER, 500_000L)
                ),
            )
        }
    }

    @Test
    fun `candidate order does not change plan identity`() {
        val first = candidate("ask", EvidenceActionKind.ASK_USER, 500_000L)
        val second = candidate("memory", EvidenceActionKind.MEMORY_LOOKUP, 600_000L)
        val args = setOf(first.kind, second.kind)

        val forward = planner.plan(
            "cycle:1",
            "budget:1",
            unresolved(),
            args,
            listOf(first, second),
        )
        val reverse = planner.plan(
            "cycle:1",
            "budget:1",
            unresolved(),
            args,
            listOf(second, first),
        )

        assertEquals(forward, reverse)
    }

    private fun unresolved(): IdentifiabilityAssessment =
        IdentifiabilityAssessment.create(
            realizationProfileFingerprint = "profile:v2.2",
            candidateIds = listOf("candidate:a", "candidate:b"),
            status = IdentifiabilityStatus.UNRESOLVED,
            sharedInterventionIds = emptyList(),
            discriminatingInterventionIds = emptyList(),
        )

    private fun candidate(
        id: String,
        kind: EvidenceActionKind,
        gain: Long,
        resource: Long = 0L,
        privacy: Long = 0L,
        risk: Long = 0L,
    ): InformationActionCandidate =
        InformationActionCandidate.create(
            interventionId = id,
            kind = kind,
            expectedInformationGainMicros = gain,
            resourceCostMicros = resource,
            privacyCostMicros = privacy,
            riskCostMicros = risk,
            reversibilityMicros = INFORMATION_SCORE_SCALE,
            rationale = "resolve:$id",
        )
}
