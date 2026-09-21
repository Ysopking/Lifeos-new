package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.reasoning.ProblemHypothesisAlternative
import app.lifeos.core.reasoning.ProblemHypothesisQuestion
import app.lifeos.core.reasoning.ProblemHypothesisSeed
import app.lifeos.core.reasoning.ProblemHypothesisSeedBuilder
import app.lifeos.core.reasoning.ProblemStateGraph
import app.lifeos.core.reasoning.ProblemStateGraphBuilder
import app.lifeos.core.reasoning.ReasoningSearchConfig
import app.lifeos.core.reasoning.ReasoningSearchEngine
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.EvidenceActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeGapDetectorTest {
    private val at = Instant.parse("2026-09-21T23:30:00Z")
    private val domainId: FieldDomainId = StableFieldIds.domain("b380-gap-test")

    @Test
    fun `explicit B366 unknown becomes non-executable knowledge gap`() {
        val problem = problem("cycle-b380")
        val result = KnowledgeGapDetector().detect(
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-b380",
                problem = problem,
            )
        )

        val gap = result.gaps.single { it.kind == KnowledgeGapKind.EXPLICIT_UNKNOWN }
        assertEquals(problem.unknowns.single().semanticKey, gap.semanticKey)
        assertTrue(EvidenceActionKind.DEEP_SEARCH in gap.recommendedEvidenceKinds)
        assertFalse(gap.executionAuthority)
        assertFalse(gap.currentCycleWorldMutationAllowed)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun `truncated reasoning search exposes truncation and no-complete-state gaps`() {
        val problem = problem("cycle-b380")
        val seed = seed(problem)
        val search = ReasoningSearchEngine(
            ReasoningSearchConfig(
                maxExpandedStates = 1,
                maxFrontierStates = 1,
            )
        ).search(seed)
        assertTrue(search.truncated)
        assertTrue(search.completeStates.isEmpty())

        val result = KnowledgeGapDetector().detect(
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-b380",
                problem = problem,
                hypothesisSeed = seed,
                search = search,
            )
        )

        assertTrue(result.gaps.any { it.kind == KnowledgeGapKind.SEARCH_TRUNCATED })
        assertTrue(result.gaps.any { it.kind == KnowledgeGapKind.NO_COMPLETE_REASONING_STATE })
    }

    @Test
    fun `search lineage substitution fails closed`() {
        val first = problem("cycle-a")
        val second = problem("cycle-b")
        val firstSeed = seed(first)
        val secondSearch = ReasoningSearchEngine().search(seed(second))

        assertFailsWith<IllegalArgumentException> {
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-a",
                problem = first,
                hypothesisSeed = firstSeed,
                search = secondSearch,
            )
        }
    }

    @Test
    fun `missing incomplete and unverified outcomes remain distinct knowledge gaps`() {
        val problem = problem("cycle-b380")
        val missing = outcomeFixture(ObservationMode.MISSING)
        val incomplete = outcomeFixture(ObservationMode.INCOMPLETE)
        val unverified = outcomeFixture(ObservationMode.UNVERIFIED)

        val missingResult = detectOutcome(problem, missing)
        val incompleteResult = detectOutcome(problem, incomplete)
        val unverifiedResult = detectOutcome(problem, unverified)

        assertTrue(missingResult.gaps.any {
            it.kind == KnowledgeGapKind.OUTCOME_OBSERVATION_MISSING
        })
        assertTrue(incompleteResult.gaps.any {
            it.kind == KnowledgeGapKind.OUTCOME_OBSERVATION_INCOMPLETE
        })
        assertTrue(unverifiedResult.gaps.any {
            it.kind == KnowledgeGapKind.OUTCOME_OBSERVATION_UNVERIFIED
        })
    }

    @Test
    fun `verified failure learning becomes experiment-oriented knowledge gap`() {
        val problem = problem("cycle-b380")
        val outside = outcomeFixture(ObservationMode.OUTSIDE)
        val failureLearning = FailureLearningEngine().learn(
            generatorId = "failure-generator",
            evaluatorId = "independent-evaluator",
            predictionReports = listOf(outside.report),
        )
        assertTrue(failureLearning.hasFailures)

        val result = KnowledgeGapDetector().detect(
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-b380",
                problem = problem,
                expectationModel = outside.model,
                predictionErrorReport = outside.report,
                failureLearningResult = failureLearning,
            )
        )

        val failureGap = result.gaps.single {
            it.kind == KnowledgeGapKind.VERIFIED_FAILURE_PATTERN
        }
        assertTrue(EvidenceActionKind.SIMULATION in failureGap.recommendedEvidenceKinds)
        assertTrue(
            EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT in
                failureGap.recommendedEvidenceKinds
        )
        assertFalse(failureGap.executionAuthority)
    }

    @Test
    fun `prediction report must match exact expectation model and source cycle`() {
        val problem = problem("cycle-b380")
        val first = outcomeFixture(ObservationMode.MISSING, sourceCycleId = "cycle-b380")
        val second = outcomeFixture(ObservationMode.MISSING, sourceCycleId = "cycle-other")

        assertFailsWith<IllegalArgumentException> {
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-b380",
                problem = problem,
                expectationModel = first.model,
                predictionErrorReport = second.report,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KnowledgeGapDetectionInput(
                sourceCycleId = "cycle-b380",
                problem = problem,
                expectationModel = second.model,
                predictionErrorReport = second.report,
            )
        }
    }

    @Test
    fun `detection is deterministic for the same exact input`() {
        val problem = problem("cycle-b380")
        val outcome = outcomeFixture(ObservationMode.MISSING)
        val input = KnowledgeGapDetectionInput(
            sourceCycleId = "cycle-b380",
            problem = problem,
            expectationModel = outcome.model,
            predictionErrorReport = outcome.report,
        )
        val detector = KnowledgeGapDetector()

        assertEquals(detector.detect(input), detector.detect(input))
    }

    private fun detectOutcome(
        problem: ProblemStateGraph,
        fixture: OutcomeFixture,
    ): KnowledgeGapDetectionResult = KnowledgeGapDetector().detect(
        KnowledgeGapDetectionInput(
            sourceCycleId = "cycle-b380",
            problem = problem,
            expectationModel = fixture.model,
            predictionErrorReport = fixture.report,
        )
    )

    private fun problem(sourceCycleId: String): ProblemStateGraph =
        ProblemStateGraphBuilder().build(
            goal = GoalFrame(
                intent = IntentType.QUERY,
                objective = "Resolve the knowledge gap.",
                entities = emptyList(),
                references = emptyList(),
                constraints = emptyList(),
                ambiguities = listOf(
                    Ambiguity(
                        code = "knowledge-gap",
                        message = "Relevant cause is unresolved.",
                        alternatives = listOf("a", "b"),
                        severity = 0.9,
                    )
                ),
                confidence = 0.9,
                language = LanguageCode.EN,
            ),
            sourcePhoton = Photon(
                id = PhotonId("problem-" + sourceCycleId),
                revision = 1L,
                content = "Resolve the knowledge gap.",
                provenance = Provenance(
                    source = "b380-test",
                    actor = "owner",
                    createdAt = at,
                ),
            ),
        )

    private fun seed(problem: ProblemStateGraph): ProblemHypothesisSeed {
        val unknown = problem.unknowns.single()
        return ProblemHypothesisSeedBuilder().build(
            problem = problem,
            domainId = domainId,
            evidence = emptyList(),
            questions = listOf(
                ProblemHypothesisQuestion(
                    unknownNodeId = unknown.id,
                    alternatives = listOf(
                        ProblemHypothesisAlternative(
                            semanticKey = "a",
                            claim = "Alternative A",
                        ),
                        ProblemHypothesisAlternative(
                            semanticKey = "b",
                            claim = "Alternative B",
                        ),
                    ),
                )
            ),
        )
    }

    private fun outcomeFixture(
        mode: ObservationMode,
        sourceCycleId: String = "cycle-b380",
    ): OutcomeFixture {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 1,
                maxTotalResourceCost = 10.0,
            )
        ).plan(
            sourceCycleId = sourceCycleId,
            reasoningSearchFingerprint = "search-" + sourceCycleId,
            counterfactualBatchFingerprint = "counterfactual-" + sourceCycleId,
            budgetFingerprint = "budget-" + sourceCycleId,
            requests = listOf(
                CausalDiscriminationRequest(
                    candidateIds = setOf("candidate-a", "candidate-b"),
                    interventionVariableId = "variable-" + sourceCycleId,
                    expectedInformationGain = 1.0,
                    rationale = "distinguish B380 fixture",
                )
            ),
        )
        val actionId = plan.items.single().evidenceAction.id
        val model = OutcomeExpectationModelBuilder().build(
            plan,
            listOf(
                OutcomeExpectationInput(
                    evidenceActionId = actionId,
                    expected = ExpectedOutcomeSignal(
                        completion = ExpectedOutcomeBand(0.6, 0.7, 0.8),
                        correctness = ExpectedOutcomeBand(0.6, 0.7, 0.8),
                    ),
                    confidence = 0.8,
                    rationale = "B380 expectation",
                )
            ),
        )
        val observations = when (mode) {
            ObservationMode.MISSING -> emptyList()
            ObservationMode.INCOMPLETE -> listOf(
                observed(
                    actionId,
                    OutcomeSignal(completion = 0.7),
                    verified = true,
                )
            )
            ObservationMode.UNVERIFIED -> listOf(
                observed(
                    actionId,
                    OutcomeSignal(completion = 0.7, correctness = 0.7),
                    verified = false,
                )
            )
            ObservationMode.OUTSIDE -> listOf(
                observed(
                    actionId,
                    OutcomeSignal(completion = 0.2, correctness = 0.2),
                    verified = true,
                )
            )
        }
        return OutcomeFixture(
            model = model,
            report = PredictionErrorEngine().compare(model, observations),
        )
    }

    private fun observed(
        actionId: String,
        signal: OutcomeSignal,
        verified: Boolean,
    ): ObservedOutcomeInput = ObservedOutcomeInput(
        evidenceActionId = actionId,
        observationRef = "observation:" + actionId,
        observationFingerprint = "observation-fingerprint:" + actionId,
        signal = signal,
        confidence = 0.9,
        verified = verified,
    )

    private enum class ObservationMode {
        MISSING,
        INCOMPLETE,
        UNVERIFIED,
        OUTSIDE,
    }

    private data class OutcomeFixture(
        val model: OutcomeExpectationModel,
        val report: PredictionErrorReport,
    )
}
