package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralSimilarityEngine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillShadowValidationEngineTest {
    private val at = Instant.parse("2026-09-21T22:30:00Z")

    @Test
    fun `generalized skill validates in shadow without promotion or activation authority`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val report = SkillShadowValidationEngine().evaluate(
            subject = subject,
            evaluatorId = "independent-skill-shadow-evaluator",
            cases = cases,
            observations = observations(subject, cases),
        )

        assertEquals(SkillShadowValidationDecision.VALIDATED, report.decision)
        assertTrue(report.shadowValidated)
        assertEquals(1, report.stats.protectedCases)
        assertEquals(1, report.stats.protectedCasesPassed)
        assertFalse(report.promotionAllowed)
        assertFalse(report.activationAllowed)
    }

    @Test
    fun `productive effect attempt is rejected even when output is correct`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val observations = observations(subject, cases).toMutableList()
        observations[0] = observations[0].copy(productiveEffectAttempted = true)

        val report = SkillShadowValidationEngine().evaluate(
            subject,
            "independent-evaluator",
            cases,
            observations,
        )

        assertEquals(SkillShadowValidationDecision.REJECTED, report.decision)
        assertTrue("productive-effect-attempted" in report.reasons)
        assertFalse(report.shadowValidated)
    }

    @Test
    fun `safety violation is rejected immediately`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val observations = observations(subject, cases).toMutableList()
        observations[1] = observations[1].copy(safetyViolation = true)

        val report = SkillShadowValidationEngine().evaluate(
            subject,
            "independent-evaluator",
            cases,
            observations,
        )

        assertEquals(SkillShadowValidationDecision.REJECTED, report.decision)
        assertTrue("safety-violation" in report.reasons)
    }

    @Test
    fun `missing or duplicate case coverage fails closed`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val observations = observations(subject, cases)

        assertFailsWith<IllegalArgumentException> {
            SkillShadowValidationEngine().evaluate(
                subject,
                "independent-evaluator",
                cases,
                observations.dropLast(1),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SkillShadowValidationEngine().evaluate(
                subject,
                "independent-evaluator",
                listOf(cases[0], cases[0], cases[2]),
                observations,
            )
        }
    }

    @Test
    fun `expected output mismatch rejects protected regression`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val observations = observations(subject, cases).toMutableList()
        observations[0] = observations[0].copy(outputFingerprint = "wrong-output")

        val report = SkillShadowValidationEngine().evaluate(
            subject,
            "independent-evaluator",
            cases,
            observations,
        )

        assertEquals(SkillShadowValidationDecision.REJECTED, report.decision)
        assertTrue(report.reasons.any { it.startsWith("expected-output-mismatch:") })
        assertEquals(0, report.stats.protectedCasesPassed)
    }

    @Test
    fun `too few otherwise valid cases remains insufficient evidence`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(2)

        val report = SkillShadowValidationEngine().evaluate(
            subject,
            "independent-evaluator",
            cases,
            observations(subject, cases),
        )

        assertEquals(SkillShadowValidationDecision.INSUFFICIENT_EVIDENCE, report.decision)
        assertFalse(report.shadowValidated)
    }

    @Test
    fun `case and observation ordering cannot change report identity`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val cases = cases(3)
        val observations = observations(subject, cases)
        val engine = SkillShadowValidationEngine()

        val first = engine.evaluate(
            subject,
            "independent-evaluator",
            cases,
            observations,
        )
        val second = engine.evaluate(
            subject,
            "independent-evaluator",
            cases.reversed(),
            observations.reversed(),
        )

        assertEquals(first, second)
    }

    @Test
    fun `observation from another skill candidate fails closed`() {
        val subject = SkillShadowSubject.from(generalizedSkill())
        val otherSubject = SkillShadowSubject.from(
            generalizedSkill(targetDomain = "domain-z")
        )
        val cases = cases(3)
        val observations = observations(subject, cases).toMutableList()
        observations[0] = observations[0].copy(subjectId = otherSubject.subjectId)

        assertFailsWith<IllegalArgumentException> {
            SkillShadowValidationEngine().evaluate(
                subject,
                "independent-evaluator",
                cases,
                observations,
            )
        }
    }

    @Test
    fun `procedural B376 candidate can be represented without generalized metadata`() {
        val procedural = sourceSkill()
        val subject = SkillShadowSubject.from(procedural)

        assertEquals(SkillShadowSubjectKind.PROCEDURAL, subject.kind)
        assertEquals(procedural.id, subject.subjectId)
        assertEquals(null, subject.sourceCandidateId)
        assertEquals(null, subject.targetDomainId)
    }

    private fun cases(count: Int): List<SkillShadowTestCase> =
        (1..count).map { index ->
            val id = "case-" + index
            SkillShadowTestCase(
                caseId = id,
                inputFingerprint = StableFieldIds.fingerprint("input", id),
                expectedOutputFingerprint = StableFieldIds.fingerprint("expected", id),
                protectedCase = index == 1,
            )
        }

    private fun observations(
        subject: SkillShadowSubject,
        cases: List<SkillShadowTestCase>,
    ): List<SkillShadowObservation> = cases.mapIndexed { index, testCase ->
        SkillShadowObservation(
            subjectId = subject.subjectId,
            caseId = testCase.caseId,
            executionMode = SkillShadowExecutionMode.SHADOW,
            outputFingerprint = testCase.expectedOutputFingerprint,
            success = true,
            quality = 0.90 - index * 0.02,
            productiveEffectAttempted = false,
            safetyViolation = false,
            latencyMs = 50L + index,
            evidenceFingerprint = StableFieldIds.fingerprint(
                "shadow-evidence",
                subject.subjectId,
                testCase.caseId,
            ),
        )
    }

    private fun generalizedSkill(
        targetDomain: String = "domain-y",
    ): GeneralizedSkillCandidate {
        val skill = sourceSkill()
        val source = StructuralSignature(
            domainId = "domain-x",
            topologyFingerprint = skill.shapeFingerprint,
            relationFingerprint = "workflow-relations",
            dimensionFingerprint = "workflow-dimensions",
        )
        val target = StructuralSignature(
            domainId = targetDomain,
            topologyFingerprint = skill.shapeFingerprint,
            relationFingerprint = "workflow-relations",
            dimensionFingerprint = "workflow-dimensions",
        )
        val transfer = StructuralSimilarityEngine().candidate(
            source,
            target,
            validationFingerprint = "validated-transfer-" + targetDomain,
        )
        return SkillGeneralizationEngine().generalize(
            listOf(
                SkillGeneralizationRequest(
                    sourceSkill = skill,
                    transfer = transfer,
                    targetObjectivesByStepKey = mapOf(
                        "inspect" to "Inspect target domain.",
                        "report" to "Report target domain.",
                    ),
                )
            )
        ).single()
    }

    private fun sourceSkill(): ProceduralSkillCandidate {
        val a = trace("cycle-a")
        val b = trace("cycle-b")
        return ProceduralSkillInductionEngine().induce(
            traces = listOf(a, b),
            semanticKeysByShape = mapOf(a.shapeFingerprint to "skill:repo-audit"),
        ).single()
    }

    private fun trace(sourceCycleId: String): ProceduralSkillTrace {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-" + sourceCycleId),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = "inspect",
                    objective = "Inspect the source workspace.",
                    priority = 10,
                ),
                GoalStepSpec(
                    key = "report",
                    objective = "Write the source report.",
                    dependencyKeys = setOf("inspect"),
                    priority = 5,
                ),
            ),
            createdAt = at,
        )
        val transitions = plan.steps.mapIndexed { index, step ->
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
        return ProceduralSkillTrace.from(
            episode = episode(sourceCycleId),
            plan = plan,
            transitions = transitions,
        )
    }

    private fun episode(sourceCycleId: String): LearningEpisode {
        val status = LearningEpisodeStatus.VERIFIED_OUTCOME
        val summary = LearningEpisodeSummary(
            expectedActions = 1,
            missingObservations = 0,
            incompleteObservations = 0,
            unverifiedObservations = 0,
            withinExpectedBand = 1,
            outsideExpectedBand = 0,
            causalAssignments = 0,
        )
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", sourceCycleId)
        )
        val hypothesis = StableFieldIds.fingerprint("hypothesis", sourceCycleId)
        val search = StableFieldIds.fingerprint("search", sourceCycleId)
        val counterfactual = StableFieldIds.fingerprint("counterfactual", sourceCycleId)
        val plan = StableFieldIds.fingerprint("plan", sourceCycleId)
        val expectation = StableFieldIds.fingerprint("expectation", sourceCycleId)
        val error = StableFieldIds.fingerprint("error", sourceCycleId)
        val fingerprint = StableFieldIds.fingerprint(
            "learning-episode/v1",
            sourceCycleId,
            "1",
            "",
            problemId.value,
            hypothesis,
            search,
            counterfactual,
            plan,
            expectation,
            error,
            "",
            status.name,
            summary.fingerprint(),
            at.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fingerprint),
            sourceCycleId = sourceCycleId,
            cycleRevision = 1L,
            predecessorId = null,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = hypothesis,
            reasoningSearchFingerprint = search,
            counterfactualBatchFingerprint = counterfactual,
            experimentPlanFingerprint = plan,
            expectationModelFingerprint = expectation,
            predictionErrorReportFingerprint = error,
            causalCreditReportFingerprint = null,
            status = status,
            summary = summary,
            createdAt = at,
        )
    }
}
