package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FailureLearningEngineTest {
    private val at = Instant.parse("2026-09-21T23:00:00Z")

    @Test
    fun `verified outside-band prediction becomes existing curriculum failure evidence`() {
        val report = predictionReport(actualCompletion = 0.20, verified = true)

        val result = FailureLearningEngine().learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            predictionReports = listOf(report),
        )

        assertTrue(result.hasFailures)
        assertEquals(1, result.failures.size)
        assertEquals(
            "prediction-outside-expected-band",
            result.failures.single().failureClass,
        )
        assertNotNull(result.curriculumCandidate)
        assertFalse(result.curriculumCandidate.strategyPromotionAllowed)
        assertFalse(result.learningAuthority)
        assertFalse(result.strategyPromotionAllowed)
        assertFalse(result.correctiveExecutionAllowed)
    }

    @Test
    fun `within-band and unverified prediction observations are not failure learning`() {
        val within = predictionReport(actualCompletion = 0.70, verified = true)
        val unverified = predictionReport(actualCompletion = 0.20, verified = false)

        val result = FailureLearningEngine().learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            predictionReports = listOf(within, unverified),
        )

        assertFalse(result.hasFailures)
        assertTrue(result.failures.isEmpty())
        assertEquals(null, result.curriculumCandidate)
    }

    @Test
    fun `rejected skill shadow becomes failure while insufficient evidence does not`() {
        val rejected = shadowReport(caseCount = 3, mismatchFirstOutput = true)
        val insufficient = shadowReport(caseCount = 2, mismatchFirstOutput = false)

        val result = FailureLearningEngine().learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            skillShadowReports = listOf(rejected, insufficient),
        )

        assertTrue(result.hasFailures)
        assertTrue(result.failures.all { it.predictionId.startsWith("skill-shadow:") })
        assertTrue(result.failures.all { it.failureClass.startsWith("skill-shadow:") })
        assertTrue(result.failures.none { it.failureClass.contains("insufficient") })
        assertNotNull(result.curriculumCandidate)
    }

    @Test
    fun `failure generator and evaluator must remain separated`() {
        assertFailsWith<IllegalArgumentException> {
            FailureLearningEngine().learn(
                generatorId = "same-identity",
                evaluatorId = "same-identity",
                predictionReports = listOf(
                    predictionReport(actualCompletion = 0.20, verified = true)
                ),
            )
        }
    }

    @Test
    fun `report ordering cannot change failure-learning identity`() {
        val prediction = predictionReport(actualCompletion = 0.20, verified = true)
        val shadow = shadowReport(caseCount = 3, mismatchFirstOutput = true)
        val engine = FailureLearningEngine()

        val first = engine.learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            predictionReports = listOf(prediction),
            skillShadowReports = listOf(shadow),
        )
        val second = engine.learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            skillShadowReports = listOf(shadow),
            predictionReports = listOf(prediction),
        )

        assertEquals(first, second)
    }

    @Test
    fun `duplicate reports fail closed instead of inflating support`() {
        val report = predictionReport(actualCompletion = 0.20, verified = true)

        assertFailsWith<IllegalArgumentException> {
            FailureLearningEngine().learn(
                generatorId = "failure-generator",
                evaluatorId = "independent-evaluator",
                predictionReports = listOf(report, report),
            )
        }
    }

    private fun predictionReport(
        actualCompletion: Double,
        verified: Boolean,
    ): PredictionErrorReport {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 1,
                maxTotalResourceCost = 10.0,
            )
        ).plan(
            sourceCycleId = "cycle-b379",
            reasoningSearchFingerprint = "search-b379",
            counterfactualBatchFingerprint = "counterfactual-b379",
            budgetFingerprint = "budget-b379",
            requests = listOf(
                CausalDiscriminationRequest(
                    candidateIds = setOf("candidate-a", "candidate-b"),
                    interventionVariableId = "variable-b379",
                    expectedInformationGain = 1.0,
                    rationale = "distinguish failure-learning fixture",
                )
            ),
        )
        val item = plan.items.single()
        val model = OutcomeExpectationModelBuilder().build(
            plan,
            listOf(
                OutcomeExpectationInput(
                    evidenceActionId = item.evidenceAction.id,
                    expected = ExpectedOutcomeSignal(
                        completion = ExpectedOutcomeBand(
                            lower = 0.60,
                            point = 0.70,
                            upper = 0.80,
                        ),
                    ),
                    confidence = 0.8,
                    rationale = "B379 expectation fixture",
                )
            ),
        )
        return PredictionErrorEngine().compare(
            model,
            listOf(
                ObservedOutcomeInput(
                    evidenceActionId = item.evidenceAction.id,
                    observationRef = "observation-b379",
                    observationFingerprint = "observation-fingerprint-b379",
                    signal = OutcomeSignal(completion = actualCompletion),
                    confidence = 0.9,
                    verified = verified,
                )
            ),
        )
    }

    private fun shadowReport(
        caseCount: Int,
        mismatchFirstOutput: Boolean,
    ): SkillShadowValidationReport {
        val subject = SkillShadowSubject.from(sourceSkill())
        val cases = (1..caseCount).map { index ->
            val id = "shadow-case-" + index
            SkillShadowTestCase(
                caseId = id,
                inputFingerprint = StableFieldIds.fingerprint("shadow-input", id),
                expectedOutputFingerprint = StableFieldIds.fingerprint("shadow-expected", id),
                protectedCase = index == 1,
            )
        }
        val observations = cases.mapIndexed { index, testCase ->
            SkillShadowObservation(
                subjectId = subject.subjectId,
                caseId = testCase.caseId,
                executionMode = SkillShadowExecutionMode.SHADOW,
                outputFingerprint = if (index == 0 && mismatchFirstOutput) {
                    "wrong-shadow-output"
                } else {
                    testCase.expectedOutputFingerprint
                },
                success = true,
                quality = 0.9,
                productiveEffectAttempted = false,
                safetyViolation = false,
                latencyMs = 50L,
                evidenceFingerprint = StableFieldIds.fingerprint(
                    "shadow-evidence",
                    testCase.caseId,
                ),
            )
        }
        return SkillShadowValidationEngine().evaluate(
            subject = subject,
            evaluatorId = "independent-shadow-evaluator",
            cases = cases,
            observations = observations,
        )
    }

    private fun sourceSkill(): ProceduralSkillCandidate {
        val a = trace("cycle-a")
        val b = trace("cycle-b")
        return ProceduralSkillInductionEngine().induce(
            traces = listOf(a, b),
            semanticKeysByShape = mapOf(a.shapeFingerprint to "skill:b379-fixture"),
        ).single()
    }

    private fun trace(sourceCycleId: String): ProceduralSkillTrace {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-" + sourceCycleId),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = "inspect",
                    objective = "Inspect fixture.",
                    priority = 10,
                ),
                GoalStepSpec(
                    key = "report",
                    objective = "Report fixture.",
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
