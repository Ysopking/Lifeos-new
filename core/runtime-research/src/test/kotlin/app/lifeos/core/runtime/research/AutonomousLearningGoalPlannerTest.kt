package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutonomousLearningGoalPlannerTest {
    @Test
    fun same_gaps_produce_same_candidates_independent_of_input_order() {
        val first = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            key = "unknown:a",
            severity = 0.8,
            evidence = listOf(EvidenceActionKind.LOCAL_RETRIEVAL, EvidenceActionKind.DEEP_SEARCH),
        )
        val second = gap(
            kind = KnowledgeGapKind.VERIFIED_FAILURE_PATTERN,
            key = "failure:b",
            severity = 0.9,
            evidence = listOf(EvidenceActionKind.SIMULATION, EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT),
        )
        val planner = AutonomousLearningGoalPlanner()

        val a = planner.plan(listOf(first, second))
        val b = planner.plan(listOf(second, first, first))

        assertEquals(a, b)
        assertEquals(2, a.candidates.size)
        assertFalse(a.executionAuthority)
        assertFalse(a.durableGoalAdmissionAuthority)
    }

    @Test
    fun planner_never_widens_b380_evidence_recommendations() {
        val source = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            key = "unknown:bounded",
            severity = 0.7,
            evidence = listOf(EvidenceActionKind.ASK_USER),
        )

        val candidate = AutonomousLearningGoalPlanner().plan(listOf(source)).candidates.single()

        assertEquals(listOf(EvidenceActionKind.ASK_USER), candidate.allowedEvidenceKinds)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.worldMutationAuthority)
    }

    @Test
    fun search_truncation_preserves_explicit_abstention_when_b380_allows_it() {
        val source = gap(
            kind = KnowledgeGapKind.SEARCH_TRUNCATED,
            key = "reasoning-search:truncated",
            severity = 0.7,
            evidence = listOf(EvidenceActionKind.SIMULATION, EvidenceActionKind.ABSTAIN),
        )

        val candidate = AutonomousLearningGoalPlanner().plan(listOf(source)).candidates.single()

        assertTrue(candidate.abstentionAllowed)
        assertEquals(LearningGoalCriterionKind.REDUCE_SEARCH_UNCERTAINTY, candidate.successCriteria.single().kind)
    }

    @Test
    fun changed_gap_identity_changes_learning_goal_identity() {
        val first = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            key = "unknown:a",
            severity = 0.8,
            evidence = listOf(EvidenceActionKind.DEEP_SEARCH),
            source = "source-a",
        )
        val second = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            key = "unknown:a",
            severity = 0.8,
            evidence = listOf(EvidenceActionKind.DEEP_SEARCH),
            source = "source-b",
        )
        val planner = AutonomousLearningGoalPlanner()

        val firstGoal = planner.plan(listOf(first)).candidates.single()
        val secondGoal = planner.plan(listOf(second)).candidates.single()

        assertNotEquals(first.id, second.id)
        assertNotEquals(firstGoal.id, secondGoal.id)
    }

    @Test
    fun truncation_is_explicit_and_deterministic() {
        val gaps = listOf(
            gap(
                kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
                key = "a",
                severity = 0.2,
                evidence = listOf(EvidenceActionKind.ASK_USER),
            ),
            gap(
                kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
                key = "b",
                severity = 0.9,
                evidence = listOf(EvidenceActionKind.ASK_USER),
            ),
        )

        val plan = AutonomousLearningGoalPlanner().plan(gaps, maxGoals = 1)

        assertTrue(plan.truncated)
        assertEquals(1, plan.candidates.size)
        assertEquals("b", plan.candidates.single().semanticKey)
    }

    @Test
    fun each_gap_kind_maps_to_a_bounded_success_criterion() {
        val evidenceByKind = mapOf(
            KnowledgeGapKind.EXPLICIT_UNKNOWN to listOf(EvidenceActionKind.ASK_USER),
            KnowledgeGapKind.SEARCH_TRUNCATED to listOf(EvidenceActionKind.SIMULATION, EvidenceActionKind.ABSTAIN),
            KnowledgeGapKind.NO_COMPLETE_REASONING_STATE to listOf(EvidenceActionKind.DEEP_SEARCH, EvidenceActionKind.ABSTAIN),
            KnowledgeGapKind.OUTCOME_OBSERVATION_MISSING to listOf(EvidenceActionKind.SOURCE_REFRESH),
            KnowledgeGapKind.OUTCOME_OBSERVATION_INCOMPLETE to listOf(EvidenceActionKind.SOURCE_REFRESH),
            KnowledgeGapKind.OUTCOME_OBSERVATION_UNVERIFIED to listOf(EvidenceActionKind.SOURCE_REFRESH),
            KnowledgeGapKind.VERIFIED_FAILURE_PATTERN to listOf(EvidenceActionKind.SIMULATION),
        )
        val gaps = KnowledgeGapKind.entries.mapIndexed { index, kind ->
            gap(
                kind = kind,
                key = "kind:$kind",
                severity = 0.5 + index * 0.01,
                evidence = evidenceByKind.getValue(kind),
            )
        }

        val plan = AutonomousLearningGoalPlanner().plan(gaps)

        assertEquals(KnowledgeGapKind.entries.size, plan.candidates.size)
        assertTrue(plan.candidates.all { it.successCriteria.isNotEmpty() })
        assertTrue(plan.candidates.all { it.maxEvidenceAttempts in 1..64 })
    }

    private fun gap(
        kind: KnowledgeGapKind,
        key: String,
        severity: Double,
        evidence: List<EvidenceActionKind>,
        source: String = "source",
    ): KnowledgeGap = KnowledgeGap.create(
        kind = kind,
        sourceCycleId = "cycle-1",
        semanticKey = key,
        rationale = "test-$kind",
        sourceFingerprint = source,
        relatedRefs = listOf("ref:$key"),
        severity = severity,
        recommendedEvidenceKinds = evidence,
    )
}
