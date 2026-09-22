package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SelfCurriculumPlannerTest {
    @Test
    fun prerequisite_always_precedes_higher_severity_dependent_goal() {
        val prerequisite = goal("prerequisite", severity = 0.2)
        val dependent = goal("dependent", severity = 1.0)

        val plan = SelfCurriculumPlanner().plan(
            listOf(
                LearningCurriculumCandidate(
                    goal = dependent,
                    prerequisiteGoalIds = listOf(prerequisite.id),
                ),
                LearningCurriculumCandidate(prerequisite),
            )
        )

        assertEquals(listOf(prerequisite.id, dependent.id), plan.orderedGoalIds)
        assertEquals(listOf(prerequisite.id), plan.blockedAtStart.getValue(dependent.id))
    }

    @Test
    fun ready_frontier_uses_severity_then_information_gain_then_effort() {
        val highSeverity = goal("high-severity", severity = 0.9)
        val highGain = goal("high-gain", severity = 0.8)
        val lowEffort = goal(
            "low-effort",
            severity = 0.8,
            kind = KnowledgeGapKind.OUTCOME_OBSERVATION_MISSING,
            evidence = listOf(EvidenceActionKind.SOURCE_REFRESH),
        )

        val plan = SelfCurriculumPlanner().plan(
            listOf(
                LearningCurriculumCandidate(lowEffort, explicitInformationGain = 0.2),
                LearningCurriculumCandidate(highGain, explicitInformationGain = 0.9),
                LearningCurriculumCandidate(highSeverity, explicitInformationGain = 0.0),
            )
        )

        assertEquals(
            listOf(highSeverity.id, highGain.id, lowEffort.id),
            plan.orderedGoalIds,
        )
    }

    @Test
    fun unknown_prerequisite_and_cycle_fail_closed() {
        val first = goal("first", severity = 0.5)
        val second = goal("second", severity = 0.5)

        assertFailsWith<IllegalArgumentException> {
            SelfCurriculumPlanner().plan(
                listOf(
                    LearningCurriculumCandidate(
                        first,
                        prerequisiteGoalIds = listOf("learning-goal:" + "a".repeat(64)),
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SelfCurriculumPlanner().plan(
                listOf(
                    LearningCurriculumCandidate(first, prerequisiteGoalIds = listOf(second.id)),
                    LearningCurriculumCandidate(second, prerequisiteGoalIds = listOf(first.id)),
                )
            )
        }
    }

    @Test
    fun ordering_is_deterministic_across_input_order() {
        val first = LearningCurriculumCandidate(goal("a", severity = 0.7))
        val second = LearningCurriculumCandidate(goal("b", severity = 0.7))

        val a = SelfCurriculumPlanner().plan(listOf(first, second))
        val b = SelfCurriculumPlanner().plan(listOf(second, first))

        assertEquals(a, b)
    }

    @Test
    fun truncation_is_explicit_and_does_not_grant_authority() {
        val first = LearningCurriculumCandidate(goal("a", severity = 0.9))
        val second = LearningCurriculumCandidate(goal("b", severity = 0.8))

        val plan = SelfCurriculumPlanner().plan(listOf(first, second), maxItems = 1)

        assertTrue(plan.truncated)
        assertEquals(1, plan.orderedGoalIds.size)
        assertEquals(1, plan.deferredGoalIds.size)
        assertFalse(plan.executionAuthority)
        assertFalse(plan.durableGoalAdmissionAuthority)
        assertFalse(plan.ownerUtilityAuthority)
    }

    @Test
    fun explicit_information_gain_changes_identity_without_becoming_owner_utility() {
        val source = goal("gain", severity = 0.6)
        val low = LearningCurriculumCandidate(source, explicitInformationGain = 0.1)
        val high = LearningCurriculumCandidate(source, explicitInformationGain = 0.9)

        assertNotEquals(low.fingerprint(), high.fingerprint())
        assertFalse(SelfCurriculumPlanner().plan(listOf(high)).ownerUtilityAuthority)
    }

    private fun goal(
        key: String,
        severity: Double,
        kind: KnowledgeGapKind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
        evidence: List<EvidenceActionKind> = listOf(EvidenceActionKind.ASK_USER),
    ): AutonomousLearningGoalCandidate {
        val gap = KnowledgeGap.create(
            kind = kind,
            sourceCycleId = "cycle",
            semanticKey = key,
            rationale = "test",
            sourceFingerprint = "source:$key",
            relatedRefs = listOf("ref:$key"),
            severity = severity,
            recommendedEvidenceKinds = evidence,
        )
        return AutonomousLearningGoalPlanner().plan(listOf(gap)).candidates.single()
    }
}
