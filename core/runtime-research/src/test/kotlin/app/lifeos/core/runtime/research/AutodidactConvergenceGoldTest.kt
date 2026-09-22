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
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.reasoning.ExperimentPlanner
import app.lifeos.core.runtime.reasoning.ExpectedOutcomeBand
import app.lifeos.core.runtime.reasoning.ExpectedOutcomeSignal
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapDetectionResult
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import app.lifeos.core.runtime.reasoning.ObservedOutcomeInput
import app.lifeos.core.runtime.reasoning.OutcomeExpectationInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutodidactConvergenceGoldTest {
    @Test
    fun full_typed_autodidact_evidence_verifies_without_granting_new_authority() {
        val study = studyProof()
        val owner = ownerProof()
        val experiment = experimentProof()
        val transfer = transferProof()
        val stableProofs: List<AutodidactGoldProof> =
            listOf(study, owner, experiment, transfer)
        val before = AutodidactSemanticCheckpoint.create(
            stableProofs,
            processEpoch = "process-before",
        )
        val after = AutodidactSemanticCheckpoint.create(
            stableProofs.reversed(),
            processEpoch = "process-after",
        )
        val recovery = AutodidactRecoveryGoldProof.from(before, after)
        val evidence = AutodidactConvergenceGoldEvidence.create(
            study = study,
            ownerAlignment = owner,
            experiment = experiment,
            transfer = transfer,
            recovery = recovery,
        )

        val marker = AutodidactConvergenceGoldVerifier.verify(evidence)

        assertTrue(marker.startsWith("AUTODIDACT-CONVERGENCE-GOLD:"))
        assertEquals(before.semanticFingerprint, after.semanticFingerprint)
        assertTrue(before.fingerprint != after.fingerprint)
    }

    @Test
    fun recovery_rejects_changed_autodidact_semantics() {
        val study = studyProof()
        val owner = ownerProof()
        val experiment = experimentProof()
        val transfer = transferProof()
        val before = AutodidactSemanticCheckpoint.create(
            listOf(study, owner, experiment, transfer),
            processEpoch = "before",
        )
        val changedOwner = ownerProof(importance = 0.2)
        val after = AutodidactSemanticCheckpoint.create(
            listOf(study, changedOwner, experiment, transfer),
            processEpoch = "after",
        )

        assertFailsWith<IllegalArgumentException> {
            AutodidactRecoveryGoldProof.from(before, after)
        }
    }

    private fun studyProof(): StudyLoopGoldProof {
        val gap = KnowledgeGap.create(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            sourceCycleId = "cycle-b390-study",
            semanticKey = "unknown:b390",
            rationale = "resolve-b390",
            sourceFingerprint = "source-b390",
            relatedRefs = listOf("ref-b390"),
            severity = 0.9,
            recommendedEvidenceKinds = listOf(EvidenceActionKind.DEEP_SEARCH),
        )
        val detection = detection(gap)
        val learning = AutonomousLearningGoalPlanner().plan(listOf(gap))
        val curriculum = SelfCurriculumPlanner().plan(
            learning.candidates.map { LearningCurriculumCandidate(it) }
        )
        val seeded = RecursiveResearchPlanner().seed(
            "cycle-b390-study",
            detection,
        )
        val mission = seeded.missions.single()
        val advanced = RecursiveResearchPlanner().advance(
            seeded,
            mapOf(mission.id to deepSearchResult(mission)),
        )
        val loop = AutonomousStudyLoop()
        val plan = loop.compose(learning, curriculum, advanced)
        val candidates = loop.consolidationCandidates(plan, learning, advanced)
        return StudyLoopGoldProof.from(plan, candidates)
    }

    private fun ownerProof(
        importance: Double = 1.0,
    ): OwnerAlignmentGoldProof {
        val profile = OwnerUtilityModel().learn(
            listOf(
                OwnerUtilityPreferenceObservation.create(
                    dimension = OwnerUtilityDimension.CORRECTNESS,
                    importance = importance,
                    confidence = 1.0,
                    evidenceKind = OwnerUtilityEvidenceKind.EXPLICIT_DECLARATION,
                    sourceFingerprint = "a".repeat(64),
                )
            )
        )
        val candidate = OwnerAlignedDecisionCandidate.create(
            goalPlanFingerprint = "b".repeat(64),
            epistemicDecisionFingerprint = "c".repeat(64),
            utilityMeasures = mapOf(OwnerUtilityDimension.CORRECTNESS to 0.9),
        )
        val evaluation = OwnerAlignedDecisionUtility().evaluate(profile, candidate)
        return OwnerAlignmentGoldProof.from(profile, listOf(evaluation))
    }

    private fun experimentProof(): ExperimentLoopGoldProof {
        val plan = ExperimentPlanner().plan(
            sourceCycleId = "cycle-b390-experiment",
            reasoningSearchFingerprint = "reasoning-b390",
            counterfactualBatchFingerprint = "counterfactual-b390",
            budgetFingerprint = "budget-b390",
            requests = listOf(
                CausalDiscriminationRequest(
                    candidateIds = setOf("causal-a", "causal-b"),
                    interventionVariableId = "variable-b390",
                    expectedInformationGain = 1.0,
                    rationale = "distinguish-b390",
                )
            ),
        )
        val item = plan.items.single()
        val loop = AutonomousExperimentLoop()
        val model = loop.prepare(
            plan,
            listOf(
                OutcomeExpectationInput(
                    evidenceActionId = item.evidenceAction.id,
                    expected = ExpectedOutcomeSignal(
                        correctness = ExpectedOutcomeBand(0.8, 0.9, 1.0),
                    ),
                    confidence = 0.9,
                    rationale = "expected-correctness",
                )
            ),
        )
        val assessment = loop.assess(
            plan,
            model,
            listOf(
                ObservedOutcomeInput(
                    evidenceActionId = item.evidenceAction.id,
                    observationRef = "photon-revision:b390",
                    observationFingerprint = "verified-b390",
                    signal = OutcomeSignal(correctness = 0.95),
                    confidence = 0.9,
                    verified = true,
                )
            ),
        )
        return ExperimentLoopGoldProof.from(listOf(assessment))
    }

    private fun transferProof(): CrossDomainTransferGoldProof {
        val registry = ReasoningStrategyRegistry()
        val descriptor = registry.all().first {
            it.kind == ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH
        }
        val sourceClass = ReasoningProblemClass.create(
            goalSemanticClass = "source",
            constraintCount = 1,
            factCount = 2,
            unknownCount = 1,
            assumptionCount = 0,
            gapKinds = listOf(KnowledgeGapKind.SEARCH_TRUNCATED),
        )
        val targetClass = ReasoningProblemClass.create(
            goalSemanticClass = "target",
            constraintCount = 1,
            factCount = 2,
            unknownCount = 1,
            assumptionCount = 0,
            gapKinds = listOf(KnowledgeGapKind.SEARCH_TRUNCATED),
        )
        val episode = ReasoningStrategyOutcomeEpisode.create(
            strategy = descriptor,
            problemClass = sourceClass,
            sourceCycleId = "cycle-b390-transfer",
            state = ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
            verificationFingerprint = "d".repeat(64),
            normalizedEffort = 0.4,
            uncertaintyReduction = 0.7,
        )
        val sourceProfile = MetaReasoningLearner(registry)
            .learn(sourceClass, listOf(episode))
            .single()
        val hypothesis = requireNotNull(
            CrossDomainTransferEngine(registry).propose(
                sourceProfile = sourceProfile,
                sourceProblemClass = sourceClass,
                targetProblemClass = targetClass,
                sourceSignature = StructuralSignature(
                    domainId = "domain-source",
                    topologyFingerprint = "topology",
                    relationFingerprint = "relations",
                    dimensionFingerprint = "dimensions",
                ),
                targetSignature = StructuralSignature(
                    domainId = "domain-target",
                    topologyFingerprint = "topology",
                    relationFingerprint = "relations",
                    dimensionFingerprint = "dimensions",
                ),
            )
        )
        return CrossDomainTransferGoldProof.from(listOf(hypothesis))
    }

    private fun detection(
        gap: KnowledgeGap,
    ): KnowledgeGapDetectionResult {
        val inputFingerprint = "autodidact-gold-input"
        val fingerprint = StableFieldIds.fingerprint(
            "knowledge-gap-detection-result/v1",
            inputFingerprint,
            gap.id,
        )
        return KnowledgeGapDetectionResult(
            inputFingerprint = inputFingerprint,
            gaps = listOf(gap),
            fingerprint = fingerprint,
        )
    }

    private fun deepSearchResult(
        mission: RecursiveResearchMission,
    ): DeepSearchResult {
        val suffix = mission.id.value.takeLast(12)
        val evidenceId = DeepSearchEvidenceId("evidence-$suffix")
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("hypothesis-$suffix"),
            requestId = mission.request.id,
            statement = "Resolved autodidact evidence",
            semanticTerms = setOf("autodidact"),
            confidence = 0.8,
            evidenceIds = setOf(evidenceId),
        )
        val branch = DeepSearchBranch(
            id = DeepSearchBranchId("branch-$suffix"),
            requestId = mission.request.id,
            parentId = null,
            sourceId = "b390-fixture",
            depth = 0,
            hypothesis = hypothesis,
            score = DeepSearchScore(
                relevance = 0.8,
                evidenceStrength = 0.8,
                sourceReliability = 0.8,
                novelty = 0.8,
                depthCost = 0.0,
                contradictionPenalty = 0.0,
                total = 0.8,
            ),
        )
        return DeepSearchResult(
            requestId = mission.request.id,
            status = DeepSearchStatus.RESOLVED,
            best = branch,
            alternatives = listOf(branch),
            evidence = emptyList(),
            trace = emptyList(),
            workUnitsUsed = 1,
            blockedSourceIds = emptySet(),
            failedSourceIds = emptySet(),
        )
    }
}
