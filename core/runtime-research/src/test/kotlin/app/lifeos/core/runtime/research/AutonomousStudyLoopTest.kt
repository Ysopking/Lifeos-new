package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchBranchId
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchScore
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapDetectionResult
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomousStudyLoopTest {
    @Test
    fun exact_b381_b382_b399_lineage_composes_without_execution_authority() {
        val fixture = fixture()
        val plan = AutonomousStudyLoop().compose(
            learningGoals = fixture.learning,
            curriculum = fixture.curriculum,
            research = fixture.research,
        )

        assertEquals(1, plan.goals.size)
        assertEquals(AutonomousStudyState.RESEARCH_PENDING, plan.goals.single().state)
        assertFalse(plan.executionAuthority)
        assertFalse(plan.networkAuthority)
        assertFalse(plan.permissionAuthority)
        assertFalse(plan.worldMutationAuthority)
        assertFalse(plan.ownerPolicyAuthority)
    }

    @Test
    fun mismatched_information_gain_plan_is_rejected() {
        val first = fixture("first")
        val second = fixture("second")
        val mission = second.research.missions.single()
        val estimate = ResearchInformationGainEstimate.create(
            planFingerprint = second.research.fingerprint,
            missionId = mission.id,
            expectedInformationGain = 0.8,
            novelty = 0.5,
            sourceReliability = 0.7,
            estimatedCost = 0.2,
            evidenceFingerprint = "a".repeat(64),
        )
        val decision = ResearchInformationGainPolicyEngine().rank(
            second.research,
            listOf(estimate),
        )

        assertFailsWith<IllegalArgumentException> {
            AutonomousStudyLoop().compose(
                learningGoals = first.learning,
                curriculum = first.curriculum,
                research = first.research,
                informationGain = decision,
            )
        }
    }

    @Test
    fun resolved_research_yields_review_candidate_not_truth() {
        val fixture = fixture()
        val root = fixture.research.missions.single()
        val advanced = RecursiveResearchPlanner().advance(
            fixture.research,
            mapOf(root.id to result(root, DeepSearchStatus.RESOLVED)),
        )
        val loop = AutonomousStudyLoop()
        val plan = loop.compose(
            learningGoals = fixture.learning,
            curriculum = fixture.curriculum,
            research = advanced,
        )
        val candidate = loop.consolidationCandidates(
            plan = plan,
            learningGoals = fixture.learning,
            research = advanced,
        ).single()

        assertEquals(AutonomousStudyState.RESOLVED_FOR_REVIEW, plan.goals.single().state)
        assertTrue(candidate.nextCycleValidationRequired)
        assertFalse(candidate.truthAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.worldMutationAuthority)
        assertFalse(candidate.promotionAuthority)
        assertEquals(root.id, candidate.researchMissionId)
    }

    @Test
    fun unresolved_or_blocked_research_never_becomes_knowledge_candidate() {
        for (status in listOf(
            DeepSearchStatus.UNRESOLVED,
            DeepSearchStatus.PERMISSION_BLOCKED,
        )) {
            val fixture = fixture("status-$status")
            val root = fixture.research.missions.single()
            val advanced = RecursiveResearchPlanner().advance(
                fixture.research,
                mapOf(root.id to result(root, status)),
            )
            val loop = AutonomousStudyLoop()
            val plan = loop.compose(
                learningGoals = fixture.learning,
                curriculum = fixture.curriculum,
                research = advanced,
            )

            assertTrue(
                loop.consolidationCandidates(
                    plan,
                    fixture.learning,
                    advanced,
                ).isEmpty()
            )
        }
    }

    @Test
    fun compose_is_deterministic_for_exact_same_inputs() {
        val fixture = fixture()
        val loop = AutonomousStudyLoop()

        val first = loop.compose(
            fixture.learning,
            fixture.curriculum,
            fixture.research,
        )
        val second = loop.compose(
            fixture.learning,
            fixture.curriculum,
            fixture.research,
        )

        assertEquals(first, second)
    }

    private fun fixture(
        key: String = "unknown",
    ): Fixture {
        val gap = KnowledgeGap.create(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            sourceCycleId = CYCLE,
            semanticKey = key,
            rationale = "resolve-$key",
            sourceFingerprint = "source-$key",
            relatedRefs = listOf("ref-$key"),
            severity = 0.9,
            recommendedEvidenceKinds = listOf(EvidenceActionKind.DEEP_SEARCH),
        )
        val detection = detection(gap)
        val learning = AutonomousLearningGoalPlanner().plan(listOf(gap))
        val curriculum = SelfCurriculumPlanner().plan(
            learning.candidates.map { LearningCurriculumCandidate(it) }
        )
        val research = RecursiveResearchPlanner().seed(CYCLE, detection)
        return Fixture(learning, curriculum, research)
    }

    private fun detection(
        vararg gaps: KnowledgeGap,
    ): KnowledgeGapDetectionResult {
        val canonical = gaps.distinctBy { it.id }.sortedWith(
            compareByDescending<KnowledgeGap> { it.severity }
                .thenBy { it.kind.name }
                .thenBy { it.semanticKey }
                .thenBy { it.id }
        )
        val inputFingerprint = "study-loop-input"
        val fingerprint = StableFieldIds.fingerprint(
            "knowledge-gap-detection-result/v1",
            inputFingerprint,
            *canonical.map { it.id }.toTypedArray(),
        )
        return KnowledgeGapDetectionResult(
            inputFingerprint = inputFingerprint,
            gaps = canonical,
            fingerprint = fingerprint,
        )
    }

    private fun result(
        mission: RecursiveResearchMission,
        status: DeepSearchStatus,
    ): DeepSearchResult {
        val best =
            if (status == DeepSearchStatus.RESOLVED || status == DeepSearchStatus.UNRESOLVED) {
                branch(mission)
            } else {
                null
            }
        return DeepSearchResult(
            requestId = mission.request.id,
            status = status,
            best = best,
            alternatives = listOfNotNull(best),
            evidence = emptyList(),
            trace = emptyList(),
            workUnitsUsed = 1,
            blockedSourceIds =
                if (status == DeepSearchStatus.PERMISSION_BLOCKED) setOf("web") else emptySet(),
            failedSourceIds = emptySet(),
        )
    }

    private fun branch(
        mission: RecursiveResearchMission,
    ): DeepSearchBranch {
        val suffix = mission.id.value.takeLast(12)
        val evidenceId = DeepSearchEvidenceId("evidence-$suffix")
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("hypothesis-$suffix"),
            requestId = mission.request.id,
            statement = "Resolved study evidence",
            semanticTerms = setOf("study"),
            confidence = 0.8,
            evidenceIds = setOf(evidenceId),
        )
        return DeepSearchBranch(
            id = DeepSearchBranchId("branch-$suffix"),
            requestId = mission.request.id,
            parentId = null,
            sourceId = "study-fixture",
            depth = 0,
            hypothesis = hypothesis,
            score = DeepSearchScore(
                relevance = 0.8,
                evidenceStrength = 0.8,
                sourceReliability = 0.8,
                novelty = 0.7,
                depthCost = 0.0,
                contradictionPenalty = 0.0,
                total = 0.8,
            ),
        )
    }

    private data class Fixture(
        val learning: AutonomousLearningGoalPlan,
        val curriculum: SelfCurriculumPlan,
        val research: RecursiveResearchPlan,
    )

    private companion object {
        const val CYCLE = "cycle-b388"
    }
}
