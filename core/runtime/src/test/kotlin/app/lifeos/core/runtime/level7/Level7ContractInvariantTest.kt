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

class Level7ContractInvariantTest {
    @Test
    fun correlationAloneNeverCreatesCausalAuthority() {
        val observation = CausalObservation(
            id = "obs-1",
            sourceSnapshotId = "before",
            targetSnapshotId = "after",
            sourceTarget = WorldTargetRef(WorldNodeKind.STRATEGY, "strategy"),
            targetTarget = WorldTargetRef(WorldNodeKind.OUTCOME, "outcome"),
            sourceDimension = WorldSignalDimension.STRATEGY_FIT,
            targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
            signedEffect = 0.4,
            confidence = 0.7,
            evidenceKind = CausalEvidenceKind.TEMPORAL_CORRELATION,
            provenanceFingerprint = "prov",
        )
        assertFailsWith<IllegalArgumentException> {
            CausalInductionCandidate.create(listOf(observation))
        }
    }

    @Test
    fun simulationCannotBecomeProductive() {
        val scenario = WorldModelNamespaceGate.counterfactual(
            baseProductiveSnapshotId = "world-1",
            baseEquationVersion = "equation-v1",
            interventionFingerprint = "intervention-v1",
        )
        assertEquals(WorldFormulaSnapshotNamespace.COUNTERFACTUAL, scenario.namespace)
        assertFalse(scenario.productiveCommitAllowed)
    }

    @Test
    fun strategyRequiresVerifiedOutcomes() {
        assertFailsWith<IllegalArgumentException> {
            StrategyLearningCandidate.create(
                strategyId = "strategy",
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
    fun protectedRootIsClassifiedCentrally() {
        val decision = ProtectedRootFirewall.evaluate(
            RootMutationRequest(
                subjectId = "candidate",
                target = MutationTarget(
                    path = "core/runtime/world/WorldFormulaModels.kt",
                    type = "WorldFormula",
                ),
                candidateFingerprint = "candidate-fp",
            )
        )
        assertTrue(decision is RootMutationDecision.BlockedProtectedRoot)
        assertEquals(
            ProtectedRootComponent.WORLD_FORMULA_SEMANTICS,
            (decision as RootMutationDecision.BlockedProtectedRoot).component,
        )
    }

    @Test
    fun generatedGoalCannotActivateWithoutAuthorityDecision() {
        val proposed = GeneratedGoal.propose(
            semanticKey = "goal",
            parentGoalId = null,
            targetField = GoalTargetField("target", "desired", 0.8),
            provenanceFingerprint = "prov",
        )
        assertEquals(GeneratedGoalState.PROPOSED, proposed.state)
        assertFalse(proposed.executionAuthority)
        assertFailsWith<IllegalArgumentException> { proposed.activate("") }
    }

    @Test
    fun boundedRehydrationRejectsMoreThanSixteenHeads() {
        assertFailsWith<IllegalArgumentException> {
            BoundedRehydrationPlan.create(
                List(17) { index ->
                    RehydrationStep(
                        kind = RehydrationStepKind.entries[index % RehydrationStepKind.entries.size],
                        exactRef = "ref-$index",
                    )
                }
            )
        }
    }

    @Test
    fun rawConfidenceRemainsSeparateFromCalibration() {
        val model = EmpiricalConfidenceCalibrator(minimumSamples = 2).fit(
            listOf(
                PredictionOutcomePair(RawConfidence(0.6), true, "e1"),
                PredictionOutcomePair(RawConfidence(0.6), false, "e2"),
            )
        )
        val calibrated = model.calibrate(RawConfidence(0.6))
        assertEquals(0.6, RawConfidence(0.6).value)
        assertTrue(calibrated.value in 0.0..1.0)
    }
}
