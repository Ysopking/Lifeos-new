package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.level7.StrategyLearningCandidate
import app.lifeos.core.runtime.level7.VerifiedWorldTransition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProceduralSkillInductionEngineTest {
    private val at = Instant.parse("2026-09-21T21:30:00Z")

    @Test
    fun `two verified independent cycles with same completed plan shape induce inactive skill`() {
        val traceA = trace("cycle-a")
        val traceB = trace("cycle-b")

        val candidate = ProceduralSkillInductionEngine().induce(
            listOf(traceB, traceA),
            semanticKeysByShape = mapOf(traceA.shapeFingerprint to "skill:repo-audit"),
        ).single()

        assertEquals("skill:repo-audit", candidate.semanticKey)
        assertEquals(listOf("cycle-a", "cycle-b"), candidate.supportingCycleIds)
        assertEquals(2, candidate.supportingEpisodeIds.size)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.activationAllowed)
        assertFalse(candidate.promotionAllowed)
    }

    @Test
    fun `two revisions from one cycle cannot fake independent procedural support`() {
        val firstEpisode = episode("cycle-a")
        val secondEpisode = episode(
            sourceCycleId = "cycle-a",
            cycleRevision = 2L,
            predecessorId = firstEpisode.id,
            createdAt = at.plusSeconds(10),
        )
        val first = trace("cycle-a", episodeOverride = firstEpisode)
        val second = trace("cycle-a", episodeOverride = secondEpisode)

        assertTrue(
            ProceduralSkillInductionEngine().induce(listOf(first, second)).isEmpty()
        )
    }

    @Test
    fun `unverified learning episode cannot become procedural trace`() {
        val plan = plan("cycle-a")
        val transitions = completedTransitions(plan, "cycle-a")
        val unverified = episode(
            "cycle-a",
            status = LearningEpisodeStatus.UNVERIFIED_OUTCOME,
        )

        assertFailsWith<IllegalArgumentException> {
            ProceduralSkillTrace.from(
                episode = unverified,
                plan = plan,
                transitions = transitions,
            )
        }
    }

    @Test
    fun `every plan step must have exactly one completed transition`() {
        val plan = plan("cycle-a")
        val transitions = completedTransitions(plan, "cycle-a").dropLast(1)

        assertFailsWith<IllegalArgumentException> {
            ProceduralSkillTrace.from(
                episode = episode("cycle-a"),
                plan = plan,
                transitions = transitions,
            )
        }
    }

    @Test
    fun `transitions from another plan fail closed`() {
        val planA = plan("cycle-a")
        val planB = plan("cycle-b")

        assertFailsWith<IllegalArgumentException> {
            ProceduralSkillTrace.from(
                episode = episode("cycle-a"),
                plan = planA,
                transitions = completedTransitions(planB, "cycle-b"),
            )
        }
    }

    @Test
    fun `trace ordering cannot change candidate identity`() {
        val a = trace("cycle-a")
        val b = trace("cycle-b")
        val engine = ProceduralSkillInductionEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a)),
        )
    }

    @Test
    fun `existing strategy learning evidence is carried without granting promotion`() {
        val strategy = StrategyLearningCandidate.create(
            strategyId = "strategy:repo-audit",
            strategyFingerprint = StableFieldIds.fingerprint("strategy", "repo-audit"),
            transitions = listOf(
                VerifiedWorldTransition(
                    beforeSnapshotId = "before",
                    afterSnapshotId = "after",
                    actionFingerprint = "action",
                    outcomeEvidenceFingerprint = "verified-outcome",
                    independentVerification = true,
                )
            ),
        )
        val a = trace("cycle-a", strategyLearning = strategy)
        val b = trace("cycle-b", strategyLearning = strategy)

        val candidate = ProceduralSkillInductionEngine().induce(listOf(a, b)).single()

        assertEquals(listOf(strategy.fingerprint()), candidate.strategyLearningFingerprints)
        assertFalse(candidate.promotionAllowed)
    }

    @Test
    fun `different plan shapes do not collapse into one skill`() {
        val a = trace("cycle-a", secondObjective = "Write the report.")
        val b = trace("cycle-b", secondObjective = "Delete the report.")

        assertTrue(ProceduralSkillInductionEngine().induce(listOf(a, b)).isEmpty())
    }

    private fun trace(
        sourceCycleId: String,
        secondObjective: String = "Write the report.",
        episodeOverride: LearningEpisode? = null,
        strategyLearning: StrategyLearningCandidate? = null,
    ): ProceduralSkillTrace {
        val plan = plan(sourceCycleId, secondObjective)
        return ProceduralSkillTrace.from(
            episode = episodeOverride ?: episode(sourceCycleId),
            plan = plan,
            transitions = completedTransitions(plan, sourceCycleId),
            strategyLearning = strategyLearning,
        )
    }

    private fun plan(
        sourceCycleId: String,
        secondObjective: String = "Write the report.",
    ): GoalPlanDefinition = GoalPlanDefinition.create(
        sourceGoalPhotonId = PhotonId("goal-" + sourceCycleId),
        sourceGoalPhotonRevision = 1L,
        stepSpecs = listOf(
            GoalStepSpec(
                key = "inspect",
                objective = "Inspect the repository.",
                priority = 10,
            ),
            GoalStepSpec(
                key = "report",
                objective = secondObjective,
                dependencyKeys = setOf("inspect"),
                priority = 5,
            ),
        ),
        createdAt = at,
    )

    private fun completedTransitions(
        plan: GoalPlanDefinition,
        sourceCycleId: String,
    ): List<GoalPlanTransition> = plan.steps.mapIndexed { index, step ->
        GoalPlanTransition.create(
            planId = plan.id,
            predecessorId = null,
            stepId = step.id,
            fromState = GoalStepState.RUNNING,
            toState = GoalStepState.COMPLETED,
            reason = "verified completion",
            sourceFingerprint = StableFieldIds.fingerprint(
                "transition-source",
                sourceCycleId,
                step.key,
            ),
            actionId = "action-" + sourceCycleId + "-" + step.key,
            actionIdempotencyKey = "idempotency-" + sourceCycleId + "-" + step.key,
            outcomePhotonId = PhotonId("outcome-" + sourceCycleId + "-" + step.key),
            createdAt = at.plusSeconds(index.toLong()),
        )
    }

    private fun episode(
        sourceCycleId: String,
        status: LearningEpisodeStatus = LearningEpisodeStatus.VERIFIED_OUTCOME,
        cycleRevision: Long = 1L,
        predecessorId: LearningEpisodeId? = null,
        createdAt: Instant = at,
    ): LearningEpisode {
        val summary = when (status) {
            LearningEpisodeStatus.INCOMPLETE_OUTCOME -> LearningEpisodeSummary(
                1, 1, 0, 0, 0, 0, 0,
            )
            LearningEpisodeStatus.UNVERIFIED_OUTCOME -> LearningEpisodeSummary(
                1, 0, 0, 1, 0, 0, 0,
            )
            LearningEpisodeStatus.VERIFIED_OUTCOME -> LearningEpisodeSummary(
                1, 0, 0, 0, 1, 0, 0,
            )
            LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT -> LearningEpisodeSummary(
                1, 0, 0, 0, 1, 0, 1,
            )
        }
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", sourceCycleId)
        )
        val hypothesis = StableFieldIds.fingerprint("hypothesis", sourceCycleId)
        val search = StableFieldIds.fingerprint("search", sourceCycleId)
        val counterfactual = StableFieldIds.fingerprint("counterfactual", sourceCycleId)
        val plan = StableFieldIds.fingerprint("plan", sourceCycleId)
        val expectation = StableFieldIds.fingerprint("expectation", sourceCycleId)
        val error = StableFieldIds.fingerprint("error", sourceCycleId)
        val causal = if (status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT) {
            StableFieldIds.fingerprint("causal", sourceCycleId)
        } else {
            null
        }
        val fingerprint = StableFieldIds.fingerprint(
            "learning-episode/v1",
            sourceCycleId,
            cycleRevision.toString(),
            predecessorId?.value.orEmpty(),
            problemId.value,
            hypothesis,
            search,
            counterfactual,
            plan,
            expectation,
            error,
            causal.orEmpty(),
            status.name,
            summary.fingerprint(),
            createdAt.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fingerprint),
            sourceCycleId = sourceCycleId,
            cycleRevision = cycleRevision,
            predecessorId = predecessorId,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = hypothesis,
            reasoningSearchFingerprint = search,
            counterfactualBatchFingerprint = counterfactual,
            experimentPlanFingerprint = plan,
            expectationModelFingerprint = expectation,
            predictionErrorReportFingerprint = error,
            causalCreditReportFingerprint = causal,
            status = status,
            summary = summary,
            createdAt = createdAt,
        )
    }
}
