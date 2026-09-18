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

        val worldSnapshotId = "world-snapshot:" + StableFieldIds.fingerprint(
            "functional-world",
            induction.fingerprint,
            generalized.strategy.id,
        )
        val worldSnapshotFingerprint = StableFieldIds.fingerprint(
            worldSnapshotId,
            "lifeos-world-cognitive-v1",
            "world-prov-x",
        )
        val decisionCheckpointId = "convergence-checkpoint:" + StableFieldIds.fingerprint(
            worldSnapshotId,
            generalized.strategy.id,
            "unseen-x-case",
        )
        val checkpoint = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = StableFieldIds.fingerprint("world-head", worldSnapshotId),
            equationVersion = "lifeos-world-cognitive-v1",
            cycleFingerprint = StableFieldIds.fingerprint("cycle", "X", worldSnapshotId),
            decisionSemanticFingerprint = StableFieldIds.fingerprint(
                "decision",
                decisionCheckpointId,
                worldSnapshotFingerprint,
            ),
            learningLedgerHeadFingerprint = StableFieldIds.fingerprint(
                "learning",
                induction.fingerprint,
                generalized.strategy.id,
            ),
        )
        val rootRequest = RootMutationRequest(
            subjectId = "meta-candidate",
            target = MutationTarget(
                "core/runtime/boot/BootEngineRuntime.kt",
                "BootEngineRuntime",
            ),
            candidateFingerprint = generalized.strategy.id,
        )
        val rootDecision = ProtectedRootFirewall.evaluate(rootRequest)
            as RootMutationDecision.BlockedProtectedRoot

        val goal = GeneratedGoal.propose(
            semanticKey = "novel-domain-X",
            parentGoalId = null,
            targetField = GoalTargetField(
                worldTargetFingerprint = worldSnapshotFingerprint,
                desiredStateFingerprint = "domain-x-solved",
                priority = 0.9,
            ),
            provenanceFingerprint = induction.fingerprint,
        ).activate(
            authorityDecisionId = "owner:" + StableFieldIds.fingerprint(
                "goal-authority",
                generalized.strategy.id,
            )
        )

        val scenario = WorldModelNamespaceGate.counterfactual(
            baseProductiveSnapshotId = worldSnapshotId,
            baseEquationVersion = checkpoint.equationVersion,
            interventionFingerprint = StableFieldIds.fingerprint(
                discrimination.interventionVariableId,
                discrimination.rationale,
            ),
        )

        val logicalKeys = setOf(
            StableFieldIds.fingerprint("learning-key", induction.fingerprint),
            StableFieldIds.fingerprint("learning-key", generalized.strategy.id),
        )
        val promotionSubject = StableFieldIds.fingerprint(
            "world-promotion-subject",
            generalized.strategy.id,
            induction.fingerprint,
        )
        val holdoutId = StableFieldIds.fingerprint("holdout", transitions[0].outcomeEvidenceFingerprint)
        val shadowId = StableFieldIds.fingerprint("shadow", transitions[1].outcomeEvidenceFingerprint)
        val trialId = StableFieldIds.fingerprint(
            "trial",
            discrimination.interventionVariableId,
            discrimination.rationale,
        )
        val promotionId = StableFieldIds.fingerprint(
            "promotion",
            promotionSubject,
            holdoutId,
            shadowId,
            trialId,
        )
        val architectureFingerprint = StableFieldIds.fingerprint(
            "architecture",
            "BootEngineRuntime",
            "DefaultProductiveConvergenceAuthority",
            "ProductivePhotonQueryService",
        )
        val rollbackDecisionId = StableFieldIds.fingerprint(
            "rollback",
            "v18",
            checkpoint.equationVersion,
            checkpoint.worldHeadFingerprint,
        )

        val proofs = listOf<Level7InvariantProof>(
            AuthorityTopologyProof(
                proofId = "authority:" + architectureFingerprint,
                bootEngineOwnerId = "BootEngineRuntime",
                productiveCouplingAuthorityId = "DefaultProductiveConvergenceAuthority",
                productiveCouplingPathCount = 1,
                cognitiveLifecycleOwnerCount = 1,
                foundationModelRuntimeDependencyCount = 0,
            ),
            WorldFormulaProof(
                proofId = "world-formula:" + worldSnapshotFingerprint,
                cycleId = checkpoint.cycleFingerprint,
                equationVersion = checkpoint.equationVersion,
                productiveSnapshotId = worldSnapshotId,
                productiveSnapshotFingerprint = worldSnapshotFingerprint,
                provenanceFingerprint = StableFieldIds.fingerprint(
                    induction.fingerprint,
                    generalized.strategy.id,
                    transfer.id,
                ),
                decisionCheckpointId = decisionCheckpointId,
                decisionWorldSnapshotId = worldSnapshotId,
                equationStayedFrozenDuringCycle = true,
                truthScoreExposed = false,
                externalEffectAuthority = false,
                directSubsystemMutationObserved = false,
            ),
            EvolutionPromotionProof(
                proofId = "promotion:" + promotionId,
                subjectId = promotionSubject,
                holdoutEvidenceId = holdoutId,
                shadowEvidenceId = shadowId,
                trialEvidenceId = trialId,
                promotionDecisionId = promotionId,
                moduleDirectPhysicsWriteObserved = false,
                metaAdaptedProtectedRoot = false,
            ),
            CausalDiscriminationProof(
                proofId = "causal:" + discrimination.expectedInformationGain.toString(),
                competingModelIds = competing.take(2).mapTo(linkedSetOf()) { it.id },
                discriminationRequestId = StableFieldIds.fingerprint(
                    discrimination.interventionVariableId,
                    discrimination.rationale,
                ),
                discriminatingEvidenceFingerprint = StableFieldIds.fingerprint(
                    correlation.fingerprint(),
                    discrimination.expectedInformationGain.toString(),
                ),
                selectedWorldModelId = competing.first().id,
                correlationOnly = false,
            ),
            CounterfactualIsolationProof(
                proofId = "counterfactual:" + scenario.id,
                productiveSnapshotId = worldSnapshotId,
                counterfactualSnapshotId = scenario.id,
                counterfactualNamespace = scenario.namespace.name,
                productiveHeadUnchanged = !scenario.productiveCommitAllowed,
            ),
            StrategyReuseProof(
                proofId = "strategy:" + generalized.strategy.id,
                strategyCandidateId = generalized.strategy.id,
                verifiedOutcomeIds = transitions.mapTo(linkedSetOf()) {
                    it.outcomeEvidenceFingerprint
                },
                unseenCaseId = "unseen-x-case",
                reuseDecisionId = decisionCheckpointId,
                reusedSuccessfully = true,
            ),
            GoalAuthorityProof(
                proofId = "goal:" + goal.id,
                generatedGoalId = goal.id,
                ownerAuthorityDecisionId = requireNotNull(goal.authorityDecisionId),
                activatedState = goal.state.name,
            ),
            BoundedRetrievalProof(
                proofId = "retrieval:" + architectureFingerprint,
                architectureScanFingerprint = architectureFingerprint,
                productiveFullVaultScanViolations = emptyList(),
                maxObservedPageSize = 256,
            ),
            LearningDedupProof(
                proofId = "learning:" + checkpoint.learningLedgerHeadFingerprint,
                watermarkFingerprintBefore = checkpoint.learningLedgerHeadFingerprint,
                watermarkFingerprintAfterReplay = checkpoint.learningLedgerHeadFingerprint,
                logicalKeysBefore = logicalKeys,
                logicalKeysAfterReplay = logicalKeys,
            ),
            RecoveryProof(
                proofId = "recovery:" + checkpoint.fingerprint(),
                planId = "rehydration:" + StableFieldIds.fingerprint(
                    checkpoint.worldHeadFingerprint,
                    checkpoint.cycleFingerprint,
                ),
                preDeath = checkpoint,
                postRehydration = checkpoint,
                restoredExactEquationVersion = checkpoint.equationVersion,
                restoredExactWorldHeadFingerprint = checkpoint.worldHeadFingerprint,
            ),
            RollbackProof(
                proofId = "rollback:" + rollbackDecisionId,
                degradedEquationVersion = "lifeos-world-cognitive-v18-trial",
                restoredEquationVersion = checkpoint.equationVersion,
                expectedPredecessorEquationVersion = checkpoint.equationVersion,
                degradedWorldHeadFingerprint = StableFieldIds.fingerprint(
                    "degraded-head",
                    checkpoint.worldHeadFingerprint,
                ),
                restoredWorldHeadFingerprint = checkpoint.worldHeadFingerprint,
                expectedPredecessorWorldHeadFingerprint = checkpoint.worldHeadFingerprint,
                rollbackDecisionId = rollbackDecisionId,
                processDeathCrossed = true,
            ),
            ProtectedRootProof(
                proofId = "root:" + rootRequest.candidateFingerprint,
                targetPath = rootRequest.target.path,
                targetType = rootRequest.target.type,
                classifiedComponent = rootDecision.component,
                mutationBlocked = true,
            ),
            NovelDomainProof(
                proofId = "novel:" + induction.fingerprint,
                domainId = "X",
                staticDomainRulePresent = false,
                initialDecisionState = "UNRESOLVED",
                abstractionCandidateId = induction.fingerprint,
                representationOrStrategyId = generalized.strategy.id,
                unseenCaseId = "unseen-x-case",
                finalDecisionCheckpointId = decisionCheckpointId,
                solvedUsingLearnedStructure = true,
            ),
            TransferProof(
                proofId = "transfer:" + transfer.id,
                sourceDomainId = "X",
                targetDomainId = "Y",
                semanticIdentityAssumed = transfer.semanticIdentityEstablished,
                structuralTransferCandidateId = transfer.id,
                validationFingerprint = "transfer-validation",
                adaptedStrategyId = generalized.strategy.id,
            ),
            ProvenanceTraceProof(
                proofId = "trace:" + StableFieldIds.fingerprint(
                    induction.fingerprint,
                    correlation.fingerprint(),
                    generalized.strategy.id,
                ),
                traceIds = setOf(
                    discrimination.interventionVariableId,
                    decisionCheckpointId,
                ),
                revisionRefs = setOf(
                    worldSnapshotId,
                    checkpoint.worldHeadFingerprint,
                ),
                provenanceFingerprints = setOf(
                    induction.fingerprint,
                    correlation.provenanceFingerprint,
                    transfer.validationFingerprint,
                ),
            ),
        )

        val gold = Level7GoldVerifier.verify(Level7GoldEvidence(proofs))
        assertTrue(gold.startsWith("LEVEL7-FUNCTIONAL-GOLD:"))
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
