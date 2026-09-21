package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapDetectionResult
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResearchInformationGainPolicyEngineTest {
    @Test
    fun `higher expected information gain ranks first when other factors match`() {
        val plan = plan("high", "low")
        val high = plan.missions.single { it.rootGapSemanticKey == "high" }
        val low = plan.missions.single { it.rootGapSemanticKey == "low" }
        val decision = ResearchInformationGainPolicyEngine().rank(
            plan,
            listOf(
                estimate(plan, low, gain = 0.2, evidence = "low"),
                estimate(plan, high, gain = 0.9, evidence = "high"),
            ),
        )

        assertEquals(high.id, decision.ranked.first().missionId)
        assertTrue(decision.ranked.first().score > decision.ranked.last().score)
        assertFalse(decision.selectionAuthority)
        assertFalse(decision.executionAuthority)
        assertFalse(decision.truthAuthority)
        assertFalse(decision.currentCycleWorldMutationAllowed)
    }

    @Test
    fun `novelty reliability and cost contribute without becoming observed truth`() {
        val plan = plan("a", "b")
        val a = plan.missions.single { it.rootGapSemanticKey == "a" }
        val b = plan.missions.single { it.rootGapSemanticKey == "b" }
        val decision = ResearchInformationGainPolicyEngine().rank(
            plan,
            listOf(
                estimate(
                    plan,
                    a,
                    gain = 0.6,
                    novelty = 0.9,
                    reliability = 0.9,
                    cost = 0.1,
                    evidence = "a",
                ),
                estimate(
                    plan,
                    b,
                    gain = 0.6,
                    novelty = 0.2,
                    reliability = 0.4,
                    cost = 0.9,
                    evidence = "b",
                ),
            ),
        )

        assertEquals(a.id, decision.ranked.first().missionId)
        assertTrue(decision.ranked.all { it.score in 0.0..1.0 })
    }

    @Test
    fun `planned mission without estimate remains explicitly unscored`() {
        val plan = plan("estimated", "missing")
        val estimated = plan.missions.single { it.rootGapSemanticKey == "estimated" }
        val missing = plan.missions.single { it.rootGapSemanticKey == "missing" }

        val decision = ResearchInformationGainPolicyEngine().rank(
            plan,
            listOf(estimate(plan, estimated, gain = 0.8, evidence = "estimated")),
        )

        assertEquals(listOf(estimated.id), decision.ranked.map { it.missionId })
        assertEquals(listOf(missing.id), decision.unscoredPlannedMissionIds)
        assertEquals(
            plan.missions.map { it.id }.sortedBy { it.value },
            decision.plannedMissionIds,
        )
    }

    @Test
    fun `estimate for another plan revision fails closed even when mission id is stable`() {
        val gaps = detection(gap("stable"))
        val firstPlan = RecursiveResearchPlanner().seed(
            CYCLE,
            gaps,
            RecursiveResearchBudget(maxDepth = 2),
        )
        val secondPlan = RecursiveResearchPlanner().seed(
            CYCLE,
            gaps,
            RecursiveResearchBudget(maxDepth = 3),
        )
        val mission = firstPlan.missions.single()
        assertEquals(mission.id, secondPlan.missions.single().id)
        assertNotEquals(firstPlan.fingerprint, secondPlan.fingerprint)
        val estimate = estimate(firstPlan, mission, gain = 0.8, evidence = "first")

        assertFailsWith<IllegalArgumentException> {
            ResearchInformationGainPolicyEngine().rank(secondPlan, listOf(estimate))
        }
    }

    @Test
    fun `estimate for unknown mission fails closed`() {
        val first = plan("first")
        val second = plan("second")
        val foreign = ResearchInformationGainEstimate.create(
            planFingerprint = second.fingerprint,
            missionId = first.missions.single().id,
            expectedInformationGain = 0.7,
            novelty = 0.5,
            sourceReliability = 0.7,
            estimatedCost = 0.4,
            evidenceFingerprint = digest("foreign"),
        )

        assertFailsWith<IllegalArgumentException> {
            ResearchInformationGainPolicyEngine().rank(second, listOf(foreign))
        }
    }

    @Test
    fun `duplicate estimate cannot inflate one mission priority`() {
        val plan = plan("one")
        val estimate = estimate(plan, plan.missions.single(), gain = 0.7, evidence = "same")

        assertFailsWith<IllegalArgumentException> {
            ResearchInformationGainPolicyEngine().rank(plan, listOf(estimate, estimate))
        }
    }

    @Test
    fun `estimate ordering cannot change ranked decision identity`() {
        val plan = plan("a", "b")
        val a = plan.missions.single { it.rootGapSemanticKey == "a" }
        val b = plan.missions.single { it.rootGapSemanticKey == "b" }
        val firstEstimate = estimate(plan, a, gain = 0.8, evidence = "a")
        val secondEstimate = estimate(plan, b, gain = 0.6, evidence = "b")
        val engine = ResearchInformationGainPolicyEngine()

        val first = engine.rank(plan, listOf(firstEstimate, secondEstimate))
        val second = engine.rank(plan, listOf(secondEstimate, firstEstimate))

        assertEquals(first, second)
    }

    @Test
    fun `evidence fingerprint changes estimate and decision identity without changing numeric score`() {
        val plan = plan("same")
        val mission = plan.missions.single()
        val firstEstimate = estimate(plan, mission, gain = 0.7, evidence = "evidence-a")
        val secondEstimate = estimate(plan, mission, gain = 0.7, evidence = "evidence-b")
        val engine = ResearchInformationGainPolicyEngine()

        val first = engine.rank(plan, listOf(firstEstimate))
        val second = engine.rank(plan, listOf(secondEstimate))

        assertEquals(first.ranked.single().score, second.ranked.single().score)
        assertNotEquals(firstEstimate.id, secondEstimate.id)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `invalid policy weights and non unit estimates fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            ResearchInformationGainPolicy(
                informationGainWeight = 0.0,
                noveltyWeight = 0.0,
                reliabilityWeight = 0.0,
            )
        }
        val plan = plan("bounded")
        val mission = plan.missions.single()
        assertFailsWith<IllegalArgumentException> {
            ResearchInformationGainEstimate.create(
                planFingerprint = plan.fingerprint,
                missionId = mission.id,
                expectedInformationGain = 1.1,
                novelty = 0.5,
                sourceReliability = 0.5,
                estimatedCost = 0.5,
                evidenceFingerprint = digest("bounded"),
            )
        }
    }

    private fun estimate(
        plan: RecursiveResearchPlan,
        mission: RecursiveResearchMission,
        gain: Double,
        novelty: Double = 0.5,
        reliability: Double = 0.7,
        cost: Double = 0.4,
        evidence: String,
    ): ResearchInformationGainEstimate = ResearchInformationGainEstimate.create(
        planFingerprint = plan.fingerprint,
        missionId = mission.id,
        expectedInformationGain = gain,
        novelty = novelty,
        sourceReliability = reliability,
        estimatedCost = cost,
        evidenceFingerprint = digest(evidence),
    )

    private fun plan(vararg keys: String): RecursiveResearchPlan =
        RecursiveResearchPlanner().seed(
            sourceCycleId = CYCLE,
            gaps = detection(*keys.map(::gap).toTypedArray()),
        )

    private fun gap(key: String): KnowledgeGap = KnowledgeGap.create(
        kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
        sourceCycleId = CYCLE,
        semanticKey = key,
        rationale = "resolve-$key",
        sourceFingerprint = "source-$key",
        relatedRefs = listOf("ref-$key"),
        severity = 0.8,
        recommendedEvidenceKinds = listOf(EvidenceActionKind.DEEP_SEARCH),
    )

    private fun detection(vararg gaps: KnowledgeGap): KnowledgeGapDetectionResult {
        val canonical = gaps.distinctBy { it.id }.sortedWith(
            compareByDescending<KnowledgeGap> { it.severity }
                .thenBy { it.kind.name }
                .thenBy { it.semanticKey }
                .thenBy { it.id }
        )
        val inputFingerprint = "b400-input"
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

    private fun digest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val CYCLE = "cycle-b400"
    }
}
