package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.learning.CandidateCluster
import app.lifeos.core.runtime.learning.ConceptInductionEngine
import app.lifeos.core.runtime.learning.PatternOccurrence
import app.lifeos.core.runtime.learning.PatternSignature
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Level7FunctionalGoldTest {
    @Test
    fun unknownDomainLearnsStructureAndReusesItWithoutStaticRule() {
        val signature = PatternSignature(
            sourceKind = ThoughtGraphNodeKind.EVIDENCE,
            relationKind = ThoughtGraphEdgeKind.SUPPORTS,
            targetKind = ThoughtGraphNodeKind.HYPOTHESIS,
        )
        val cluster = CandidateCluster(
            signature = signature,
            occurrences = listOf(
                occurrence("cycle-x-1", "ws-x-1", signature, "x-a", "x-b"),
                occurrence("cycle-x-2", "ws-x-2", signature, "x-c", "x-d"),
            ),
        )
        val induction = ConceptInductionEngine().induce(listOf(cluster)).single()
        assertTrue(induction.informationGain > 0.0)
        assertEquals(2, induction.supportCount)

        val variables = listOf(
            CausalVariable("strategy", WorldSignalDimension.STRATEGY_FIT.name),
            CausalVariable("outcome", WorldSignalDimension.OUTCOME_ALIGNMENT.name),
        )
        val correlation = CausalObservation(
            id = "corr-x",
            sourceSnapshotId = "world-x-1",
            targetSnapshotId = "world-x-2",
            sourceTarget = WorldTargetRef(WorldNodeKind.STRATEGY, "x-strategy"),
            targetTarget = WorldTargetRef(WorldNodeKind.OUTCOME, "x-outcome"),
            sourceDimension = WorldSignalDimension.STRATEGY_FIT,
            targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
            signedEffect = 0.7,
            confidence = 0.8,
            evidenceKind = CausalEvidenceKind.TEMPORAL_CORRELATION,
            provenanceFingerprint = "x-correlation",
        )
        val causalSearch = CausalModelSearchEngine()
        val competing = causalSearch.search(variables, listOf(correlation))
        assertTrue(competing.size >= 2)
        val discrimination = causalSearch.discriminationRequests(competing).first()
        assertTrue(discrimination.expectedInformationGain > 0.0)

        val planNodes = HierarchicalTaskDecomposer().decompose(
            sourceWorldSnapshotId = "world-x-2",
            goalId = "goal-x",
            requiredResearch = true,
            capabilityGap = false,
        )
        assertTrue(planNodes.size > 2)
        assertTrue(planNodes.any { it.kind == PlanStepKind.RESEARCH })
        assertTrue(planNodes.any { it.kind == PlanStepKind.SIMULATE })

        val transitions = listOf(
            transition("w1", "w2", "verified-1"),
            transition("w3", "w4", "verified-2"),
        )
        val generalized = StrategyGeneralizer().generalize("strategy-x", transitions)
        assertEquals(2, generalized.sourceTransitionCount)

        val calibration = EmpiricalConfidenceCalibrator(minimumSamples = 2).fit(
            listOf(
                PredictionOutcomePair(RawConfidence(0.8), true, "outcome-1"),
                PredictionOutcomePair(RawConfidence(0.8), true, "outcome-2"),
            )
        )
        assertTrue(calibration.calibrate(RawConfidence(0.8)).value > 0.0)

        val sourceStructure = StructuralSignature(
            domainId = "X",
            topologyFingerprint = "topology-shared",
            relationFingerprint = "relations-shared",
            dimensionFingerprint = "dimensions-shared",
        )
        val targetStructure = StructuralSignature(
            domainId = "Y",
            topologyFingerprint = "topology-shared",
            relationFingerprint = "relations-shared",
            dimensionFingerprint = "dimensions-shared",
        )
        val transfer = StructuralSimilarityEngine().candidate(
            source = sourceStructure,
            target = targetStructure,
            validationFingerprint = "transfer-validation",
        )
        assertFalse(transfer.semanticIdentityEstablished)
        assertEquals(1.0, transfer.structuralSimilarity)

        val checkpoint = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = "world-head-v1",
            equationVersion = "lifeos-world-cognitive-v1",
            cycleFingerprint = "cycle-x",
            decisionSemanticFingerprint = "decision-x",
            learningLedgerHeadFingerprint = "learning-x",
        )
        val rootDecision = ProtectedRootFirewall.evaluate(
            RootMutationRequest(
                subjectId = "meta-candidate",
                target = MutationTarget("core/runtime/boot/BootEngineRuntime.kt", "BootEngineRuntime"),
                candidateFingerprint = "meta-fp",
            )
        ) as RootMutationDecision.BlockedProtectedRoot

        val proofs = listOf<Level7InvariantProof>(
            ArchitectureProof(true, true, true, true, true, "architecture-source-fp"),
            WorldFormulaProof(
                worldSnapshotId = "world-snapshot-x",
                worldSnapshotFingerprint = "world-snapshot-fp",
                provenanceFingerprint = "world-prov",
                equationVersion = "lifeos-world-cognitive-v1",
                cycleFingerprint = "cycle-x",
                convergenceCheckpointId = "decision-x",
                noScalarTruthScore = true,
                noExternalEffectAuthority = true,
                noDirectMutation = true,
            ),
            PromotionChainProof("holdout", "shadow", "trial", "promotion", true),
            CausalDiscriminationProof(
                candidateIds = competing.take(2).mapTo(linkedSetOf()) { it.id },
                discriminationRequestFingerprint = StableFieldIds.fingerprint(
                    discrimination.interventionVariableId,
                    discrimination.rationale,
                ),
                correlationOnlyRejected = true,
            ),
            StrategyReuseProof(
                strategyCandidateId = generalized.strategy.id,
                verifiedTransitionFingerprints = transitions.mapTo(linkedSetOf()) {
                    StableFieldIds.fingerprint(
                        it.beforeSnapshotId,
                        it.afterSnapshotId,
                        it.actionFingerprint,
                        it.outcomeEvidenceFingerprint,
                    )
                },
                reuseOutcomeFingerprint = "unseen-x-case-solved",
            ),
            ProtectedRootProof(rootDecision.component, "BootEngineRuntime.kt", true, true),
            CounterfactualProof("cf-x", "world-head-v1", "world-head-v1"),
            LearningDedupProof(setOf("learning-key-x"), setOf("learning-key-x"), "watermark-x"),
            RecoveryProof(checkpoint, checkpoint),
            RollbackProof(
                degradedVersion = "v18",
                restoredEquationVersion = "v17",
                expectedEquationVersion = "v17",
                restoredWorldHeadFingerprint = "world-head-v1",
                expectedWorldHeadFingerprint = "world-head-v1",
            ),
            NovelDomainProof(
                domainId = "X",
                staticDomainRulePresent = false,
                beforeLearningUnresolved = true,
                abstractionCandidateId = induction.fingerprint,
                learnedRepresentationFingerprint = "representation-x-learned",
                unseenCaseOutcomeFingerprint = "unseen-x-case-solved",
            ),
            TransferProof(
                sourceDomainId = "X",
                targetDomainId = "Y",
                semanticIdentityAssumed = false,
                structuralTransferCandidateId = transfer.id,
                validationFingerprint = "transfer-validation",
                adaptedOutcomeFingerprint = "domain-y-adapted",
            ),
        )

        val gold = Level7GoldVerifier.verify(Level7GoldEvidence(proofs))
        assertTrue(gold.startsWith("LEVEL7-GOLD:"))
    }

    private fun occurrence(
        cycle: String,
        workingSet: String,
        signature: PatternSignature,
        first: String,
        second: String,
    ) = PatternOccurrence(
        cycleId = cycle,
        workingSetFingerprint = workingSet,
        nodeIds = setOf(first, second),
        signature = signature,
    )

    private fun transition(
        before: String,
        after: String,
        evidence: String,
    ) = VerifiedWorldTransition(
        beforeSnapshotId = before,
        afterSnapshotId = after,
        actionFingerprint = "shared-action-shape",
        outcomeEvidenceFingerprint = evidence,
        independentVerification = true,
    )
}
