package app.lifeos.core.runtime.level7

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Level7PreparedStackInvariantTest {
    @Test
    fun b166CorrelationAloneNeverCreatesCausalAuthority() {
        val observation = CausalObservation(
            id = "obs-1",
            sourceSnapshotId = "before",
            targetSnapshotId = "after",
            sourceTarget = WorldTargetRef(WorldNodeKind.STRATEGY, "strategy-a"),
            targetTarget = WorldTargetRef(WorldNodeKind.OUTCOME, "outcome-b"),
            sourceDimension = WorldSignalDimension.STRATEGY_FIT,
            targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
            signedEffect = 0.4,
            confidence = 0.7,
            evidenceKind = CausalEvidenceKind.TEMPORAL_CORRELATION,
            provenanceFingerprint = "prov-1",
        )

        assertFailsWith<IllegalArgumentException> {
            CausalInductionCandidate.create(listOf(observation))
        }
    }

    @Test
    fun b167SimulationNamespaceCannotBeProductive() {
        val scenario = WorldModelNamespaceGate.counterfactual(
            baseProductiveSnapshotId = "world-1",
            baseEquationVersion = "equation-v1",
            interventionFingerprint = "intervention-v1",
        )

        assertEquals(WorldFormulaSnapshotNamespace.COUNTERFACTUAL, scenario.namespace)
        assertFalse(scenario.productiveCommitAllowed)
    }

    @Test
    fun b168HierarchicalPlanHasNoExecutionOrOwnerAuthority() {
        val plan = HierarchicalWorldPlan.create(
            sourceWorldSnapshotId = "world-1",
            goalId = "goal-1",
            steps = listOf(
                WorldPlanStep(
                    id = "step-1",
                    parentId = null,
                    target = WorldStateTarget("target-a", "state-a", "accept-a"),
                    predictedTransitionFingerprint = "transition-a",
                    requiredEvidenceFingerprint = "evidence-a",
                )
            ),
        )

        assertFalse(plan.executionAuthority)
        assertFalse(plan.ownerAuthority)
    }

    @Test
    fun b169StrategyLearningRequiresVerifiedOutcomes() {
        assertFailsWith<IllegalArgumentException> {
            StrategyLearningCandidate.create(
                strategyId = "strategy-a",
                strategyFingerprint = "strategy-fp",
                transitions = listOf(
                    VerifiedWorldTransition(
                        beforeSnapshotId = "before",
                        afterSnapshotId = "after",
                        actionFingerprint = "action",
                        outcomeEvidenceFingerprint = "outcome",
                        independentVerification = false,
                    )
                ),
            )
        }
    }

    @Test
    fun b170MetaAdaptationCannotMutateProtectedRoot() {
        val candidate = MetaAdaptationCandidate.create(
            target = MetaAdaptationTarget.ATTENTION_POLICY,
            baselineFingerprint = "baseline",
            proposedFingerprint = "proposed",
            evidenceFingerprint = "evidence",
            evaluatorFingerprint = "evaluator",
        )

        assertFalse(candidate.protectedRootMutationAllowed)
        assertFalse(candidate.activationAllowed)
    }

    @Test
    fun b171RawConfidenceRemainsSeparateFromCalibration() {
        val record = ConfidenceCalibrator.calibrate(
            sourceId = "source",
            raw = RawConfidence(0.6),
            calibrationModelFingerprint = "calibration-model",
            evidenceFingerprint = "evidence",
        ) { 0.8 }

        assertEquals(0.6, record.raw.value)
        assertEquals(0.8, record.calibrated.value)
        assertTrue(record.rawConfidencePreserved)
        assertFalse(record.truthScoreExposed)
    }

    @Test
    fun b172EvidenceObservationEntersNextCycleOnly() {
        val request = EvidenceActionRequest.create(
            sourceCycleId = "cycle-1",
            gapFingerprint = "gap-1",
            kind = EvidenceActionKind.DEEP_SEARCH,
            rationale = "resolve evidence gap",
            budgetFingerprint = "budget-1",
        )
        val observation = EvidenceObservation(
            requestId = request.id,
            observationPhotonRef = "photon-1@1",
            observationFingerprint = "observation-1",
            verified = true,
        )

        assertFalse(request.executionAuthority)
        assertFalse(request.currentCycleWorldMutationAllowed)
        assertTrue(observation.entersNextCycleOnly)
    }

    @Test
    fun b173StructuralEquivalenceNeverImpliesSemanticIdentity() {
        val candidate = StructuralTransferCandidate.create(
            source = StructuralSignature("domain-a", "topology-a", "relations-a", "dimensions-a"),
            target = StructuralSignature("domain-b", "topology-b", "relations-b", "dimensions-b"),
            structuralSimilarity = 0.93,
            validationFingerprint = "validation",
        )

        assertFalse(candidate.semanticIdentityEstablished)
        assertFalse(candidate.directTransferActivationAllowed)
    }

    @Test
    fun b174CurriculumSeparatesGeneratorAndEvaluator() {
        val failure = PredictionFailure(
            predictionId = "prediction-1",
            expectedFingerprint = "expected",
            observedFingerprint = "observed",
            failureClass = "model-error",
            evidenceFingerprint = "evidence",
        )
        val candidate = CurriculumCandidate.create(
            generatorId = "generator",
            evaluatorId = "evaluator",
            failures = listOf(failure),
            curriculumFingerprint = "curriculum",
        )

        assertFalse(candidate.strategyPromotionAllowed)
        assertFailsWith<IllegalArgumentException> {
            CurriculumCandidate.create(
                generatorId = "same",
                evaluatorId = "same",
                failures = listOf(failure),
                curriculumFingerprint = "invalid",
            )
        }
    }

    @Test
    fun b175DynamicModulesAreFieldSourcesNotAuthorities() {
        val descriptor = CognitiveModuleDescriptor.create(
            moduleId = "module-a",
            version = "1.0.0",
            outputKinds = setOf(
                CognitiveModuleOutputKind.PHOTON,
                CognitiveModuleOutputKind.WORLD_SIGNAL,
            ),
            contractFingerprint = "contract",
        )

        assertFalse(descriptor.directWorldStateMutationAllowed)
        assertFalse(descriptor.convergenceAuthority)
        assertFalse(descriptor.ownerAuthority)
        assertFalse(descriptor.executionAuthority)
    }

    @Test
    fun b176MemoryFabricProjectsTypedSignalsWithoutTruthAuthority() {
        val projection = CognitiveMemoryWorldProjection.create(
            memorySnapshotId = "memory-head-1",
            observations = listOf(
                CognitiveMemoryObservation(
                    memoryId = "memory-1",
                    revision = 2,
                    kind = CognitiveMemoryKind.SEMANTIC,
                    target = WorldTargetRef(WorldNodeKind.MEMORY, "memory-1"),
                    dimensions = mapOf(WorldSignalDimension.CONTEXT_RELEVANCE to 0.7),
                    confidence = 0.8,
                    provenanceFingerprint = "prov",
                )
            ),
        )

        assertFalse(projection.directWorldStateMutationAllowed)
        assertFalse(projection.truthAuthority)
    }

    @Test
    fun b177GeneratedGoalStartsProposedAndNeedsAuthorityToActivate() {
        val proposed = GeneratedGoal.propose(
            semanticKey = "goal-a",
            parentGoalId = null,
            targetField = GoalTargetField(
                worldTargetFingerprint = "target",
                desiredStateFingerprint = "desired",
                priority = 0.8,
            ),
            provenanceFingerprint = "prov",
        )

        assertEquals(GeneratedGoalState.PROPOSED, proposed.state)
        assertFalse(proposed.executionAuthority)

        val active = proposed.activate("owner-policy-decision-1")
        assertEquals(GeneratedGoalState.ACTIVE, active.state)
        assertEquals("owner-policy-decision-1", active.authorityDecisionId)
    }

    @Test
    fun b178ProtectedRootAlwaysBlocksMutation() {
        val decision = ProtectedRootFirewall.evaluate(
            RootMutationRequest(
                subjectId = "candidate",
                component = ProtectedRootComponent.WORLD_FORMULA_SEMANTICS,
                candidateFingerprint = "candidate-fp",
            )
        )

        assertTrue(decision is RootMutationDecision.BlockedProtectedRoot)
    }

    @Test
    fun b179RehydrationIsBoundedAndExactRefOnly() {
        val plan = BoundedRehydrationPlan.create(
            listOf(
                RehydrationStep(RehydrationStepKind.PRODUCTIVE_WORLD_HEAD, "head@7"),
                RehydrationStep(RehydrationStepKind.PRODUCTIVE_WORLD_SNAPSHOT, "snapshot-abc"),
                RehydrationStep(RehydrationStepKind.ACTIVE_BOOTENGINE_CYCLE, "cycle-9"),
            )
        )

        assertFalse(plan.fullVaultScanAllowed)
        assertTrue(plan.steps.size <= BoundedRehydrationPlan.MAX_STEPS)
    }

    @Test
    fun b180GoldRequiresExactSemanticRehydrationAndNoDuplicateLearning() {
        val checkpoint = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = "world-head",
            equationVersion = "lifeos-world-cognitive-v1",
            cycleFingerprint = "cycle",
            decisionSemanticFingerprint = "decision",
            learningLedgerHeadFingerprint = "learning",
        )
        val evidence = Level7GoldEvidence(
            invariantEvidence = Level7GoldInvariants.REQUIRED.associateWith { "evidence:$it" },
            preDeath = checkpoint,
            postRehydration = checkpoint,
            logicalLearningKeysBefore = setOf("learning:1", "learning:2"),
            logicalLearningKeysAfter = setOf("learning:1", "learning:2"),
        )

        val gold = Level7GoldVerifier.verify(evidence)

        assertTrue(gold.startsWith("LEVEL7-GOLD:"))
        assertEquals(24, Level7GoldInvariants.REQUIRED.size)
    }
}
