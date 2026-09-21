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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecursiveResearchPlannerTest {
    @Test
    fun `seed creates research missions only for Web-research evidence kinds`() {
        val deep = gap("deep", 0.9, EvidenceActionKind.DEEP_SEARCH)
        val refresh = gap("refresh", 0.8, EvidenceActionKind.SOURCE_REFRESH)
        val simulation = gap("simulate", 1.0, EvidenceActionKind.SIMULATION)
        val plan = RecursiveResearchPlanner().seed(
            sourceCycleId = CYCLE,
            gaps = detection(simulation, refresh, deep),
        )

        assertEquals(2, plan.missions.size)
        assertEquals(setOf(deep.id, refresh.id), plan.missions.map { it.rootGapId }.toSet())
        assertEquals(listOf(simulation.id), plan.nonResearchGapIds)
        assertTrue(plan.budgetDeferredGapIds.isEmpty())
        assertFalse(plan.executionAuthority)
        assertFalse(plan.permissionAuthority)
        assertFalse(plan.currentCycleWorldMutationAllowed)
        assertTrue(plan.missions.all { !it.executionAuthority })
    }

    @Test
    fun `seed obeys global mission budget and records eligible deferred gaps`() {
        val high = gap("high", 0.9, EvidenceActionKind.DEEP_SEARCH)
        val low = gap("low", 0.4, EvidenceActionKind.DEEP_SEARCH)
        val plan = RecursiveResearchPlanner().seed(
            sourceCycleId = CYCLE,
            gaps = detection(low, high),
            budget = RecursiveResearchBudget(maxMissions = 1),
        )

        assertEquals(listOf(high.id), plan.missions.map { it.rootGapId })
        assertEquals(listOf(low.id), plan.budgetDeferredGapIds)
    }

    @Test
    fun `unresolved mission creates exactly one bounded recursive follow-up`() {
        val rootGap = gap("cause", 0.9, EvidenceActionKind.DEEP_SEARCH)
        val seeded = RecursiveResearchPlanner().seed(CYCLE, detection(rootGap))
        val root = seeded.missions.single()
        val result = result(root, DeepSearchStatus.UNRESOLVED, bestStatement = "Candidate explanation")

        val advanced = RecursiveResearchPlanner().advance(seeded, mapOf(root.id to result))

        assertEquals(2, advanced.missions.size)
        val parent = advanced.missions.single { it.id == root.id }
        val child = advanced.missions.single { it.parentMissionId == root.id }
        assertEquals(RecursiveResearchMissionStatus.FOLLOW_UP_PLANNED, parent.status)
        assertEquals(DeepSearchStatus.UNRESOLVED, parent.resultStatus)
        assertEquals(1, child.depth)
        assertEquals(root.rootGapId, child.rootGapId)
        assertTrue(child.request.query.contains("cause"))
        assertTrue(child.request.query.contains("Candidate explanation"))
        assertEquals(seeded.budget.perMissionBudget, child.request.budget)
    }

    @Test
    fun `resolved mission is terminal and produces no child`() {
        val seeded = seeded("resolved")
        val root = seeded.missions.single()
        val advanced = RecursiveResearchPlanner().advance(
            seeded,
            mapOf(root.id to result(root, DeepSearchStatus.RESOLVED, "Resolved answer")),
        )

        assertEquals(1, advanced.missions.size)
        assertEquals(RecursiveResearchMissionStatus.RESOLVED, advanced.missions.single().status)
    }

    @Test
    fun `permission and source blocks remain terminal instead of recursive bypass`() {
        for (status in listOf(DeepSearchStatus.PERMISSION_BLOCKED, DeepSearchStatus.NO_USABLE_SOURCE)) {
            val seeded = seeded("blocked-${status.name}")
            val root = seeded.missions.single()
            val advanced = RecursiveResearchPlanner().advance(
                seeded,
                mapOf(root.id to result(root, status)),
            )

            assertEquals(1, advanced.missions.size)
            assertEquals(RecursiveResearchMissionStatus.BLOCKED, advanced.missions.single().status)
        }
    }

    @Test
    fun `per mission work or time exhaustion cannot be bypassed by recursion`() {
        for (status in listOf(
            DeepSearchStatus.WORK_BUDGET_EXHAUSTED,
            DeepSearchStatus.TIME_BUDGET_EXHAUSTED,
        )) {
            val seeded = seeded("exhausted-${status.name}")
            val root = seeded.missions.single()
            val advanced = RecursiveResearchPlanner().advance(
                seeded,
                mapOf(root.id to result(root, status)),
            )

            assertEquals(1, advanced.missions.size)
            assertEquals(
                RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED,
                advanced.missions.single().status,
            )
        }
    }

    @Test
    fun `max recursive depth converts unresolved result to terminal`() {
        val seeded = RecursiveResearchPlanner().seed(
            sourceCycleId = CYCLE,
            gaps = detection(gap("depth", 0.9, EvidenceActionKind.DEEP_SEARCH)),
            budget = RecursiveResearchBudget(maxDepth = 0),
        )
        val root = seeded.missions.single()
        val advanced = RecursiveResearchPlanner().advance(
            seeded,
            mapOf(root.id to result(root, DeepSearchStatus.UNRESOLVED)),
        )

        assertEquals(1, advanced.missions.size)
        assertEquals(
            RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED,
            advanced.missions.single().status,
        )
    }

    @Test
    fun `wrong DeepSearch request lineage fails closed`() {
        val first = seeded("first")
        val second = seeded("second")
        val firstMission = first.missions.single()
        val wrongResult = result(second.missions.single(), DeepSearchStatus.UNRESOLVED)

        assertFailsWith<IllegalArgumentException> {
            RecursiveResearchPlanner().advance(first, mapOf(firstMission.id to wrongResult))
        }
    }

    @Test
    fun `exact result replay is idempotent but changed replay fails closed`() {
        val seeded = seeded("replay")
        val root = seeded.missions.single()
        val original = result(root, DeepSearchStatus.UNRESOLVED, workUnits = 1)
        val planner = RecursiveResearchPlanner()
        val advanced = planner.advance(seeded, mapOf(root.id to original))

        assertEquals(advanced, planner.advance(advanced, mapOf(root.id to original)))

        val changed = result(root, DeepSearchStatus.UNRESOLVED, workUnits = 2)
        assertNotEquals(original.workUnitsUsed, changed.workUnitsUsed)
        assertFailsWith<IllegalArgumentException> {
            planner.advance(advanced, mapOf(root.id to changed))
        }
    }

    @Test
    fun `same exact gap input produces deterministic plan identity`() {
        val gaps = detection(
            gap("a", 0.7, EvidenceActionKind.DEEP_SEARCH),
            gap("b", 0.8, EvidenceActionKind.SOURCE_REFRESH),
        )
        val planner = RecursiveResearchPlanner()

        assertEquals(planner.seed(CYCLE, gaps), planner.seed(CYCLE, gaps))
    }

    private fun seeded(key: String): RecursiveResearchPlan =
        RecursiveResearchPlanner().seed(
            CYCLE,
            detection(gap(key, 0.9, EvidenceActionKind.DEEP_SEARCH)),
        )

    private fun gap(
        key: String,
        severity: Double,
        kind: EvidenceActionKind,
    ): KnowledgeGap = KnowledgeGap.create(
        kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
        sourceCycleId = CYCLE,
        semanticKey = key,
        rationale = "resolve-$key",
        sourceFingerprint = "source-$key",
        relatedRefs = listOf("ref-$key"),
        severity = severity,
        recommendedEvidenceKinds = listOf(kind),
    )

    private fun detection(vararg gaps: KnowledgeGap): KnowledgeGapDetectionResult {
        val canonical = gaps.distinctBy { it.id }.sortedWith(
            compareByDescending<KnowledgeGap> { it.severity }
                .thenBy { it.kind.name }
                .thenBy { it.semanticKey }
                .thenBy { it.id }
        )
        val inputFingerprint = "input-fingerprint"
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
        bestStatement: String = "Candidate",
        workUnits: Int = 1,
    ): DeepSearchResult {
        val best = if (status == DeepSearchStatus.RESOLVED || status == DeepSearchStatus.UNRESOLVED) {
            branch(mission, bestStatement)
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
            workUnitsUsed = workUnits,
            blockedSourceIds = if (status == DeepSearchStatus.PERMISSION_BLOCKED) setOf("web") else emptySet(),
            failedSourceIds = emptySet(),
        )
    }

    private fun branch(
        mission: RecursiveResearchMission,
        statement: String,
    ): DeepSearchBranch {
        val evidenceId = DeepSearchEvidenceId("evidence-${mission.id.value.takeLast(12)}")
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("hypothesis-${mission.id.value.takeLast(12)}"),
            requestId = mission.request.id,
            statement = statement,
            semanticTerms = setOf("candidate"),
            confidence = 0.7,
            evidenceIds = setOf(evidenceId),
        )
        return DeepSearchBranch(
            id = DeepSearchBranchId("branch-${mission.id.value.takeLast(12)}"),
            requestId = mission.request.id,
            parentId = null,
            sourceId = "fixture-source",
            depth = 0,
            hypothesis = hypothesis,
            score = DeepSearchScore(
                relevance = 0.7,
                evidenceStrength = 0.7,
                sourceReliability = 0.7,
                novelty = 0.7,
                depthCost = 0.0,
                contradictionPenalty = 0.0,
                total = 0.7,
            ),
        )
    }

    companion object {
        private const val CYCLE = "cycle-b399"
    }
}
