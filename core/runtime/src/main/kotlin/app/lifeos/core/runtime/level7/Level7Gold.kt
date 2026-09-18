package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

object Level7GoldInvariants {
    val REQUIRED: Set<String> = linkedSetOf(
        "NO_FOUNDATION_MODEL_RUNTIME_DEPENDENCY",
        "BOOTENGINE_IS_SINGLE_LIFECYCLE_OWNER",
        "WORLD_FORMULA_IS_SINGLE_COGNITIVE_COUPLING_LAYER",
        "NO_SECOND_COGNITIVE_CONTROL_PLANE",
        "NO_DIRECT_SUBSYSTEM_WORLD_STATE_MUTATION",
        "NO_WORLD_FORMULA_SCALAR_TRUTH_SCORE",
        "NO_WORLD_FORMULA_EXTERNAL_EFFECT_AUTHORITY",
        "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT",
        "NO_WORLD_SNAPSHOT_WITHOUT_PROVENANCE",
        "NO_WORLD_EQUATION_CHANGE_DURING_ACTIVE_CYCLE",
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_HOLDOUT",
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_SHADOW",
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_TRIAL",
        "NO_CAUSAL_COEFFICIENT_FROM_CORRELATION_ONLY",
        "NO_SIMULATION_SNAPSHOT_AS_PRODUCTIVE_WORLD_STATE",
        "NO_STRATEGY_PROMOTION_WITHOUT_VERIFIED_OUTCOME",
        "NO_META_ADAPTATION_OF_PROTECTED_ROOT",
        "NO_MODULE_DIRECTLY_WRITES_WORLD_PHYSICS",
        "NO_SYSTEM_GOAL_BYPASSES_OWNER_AUTHORITY",
        "NO_PRODUCTIVE_FULL_VAULT_SCAN",
        "NO_DUPLICATE_LOGICAL_LEARNING_AFTER_RESTART",
        "NO_UNVERSIONED_WORLD_PHYSICS",
        "ROLLBACK_RESTORES_EXACT_EQUATION_AND_WORLD_HEAD",
        "PROCESS_DEATH_MUST_NOT_CHANGE_DECISION_SEMANTICS",
    )
}

sealed interface Level7InvariantProof {
    val proofId: String
    val invariantIds: Set<String>
    fun fingerprint(): String
}

data class AuthorityTopologyProof(
    override val proofId: String,
    val bootEngineOwnerId: String,
    val productiveCouplingAuthorityId: String,
    val productiveCouplingPathCount: Int,
    val cognitiveLifecycleOwnerCount: Int,
    val foundationModelRuntimeDependencyCount: Int,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(bootEngineOwnerId.isNotBlank())
        require(productiveCouplingAuthorityId.isNotBlank())
        require(productiveCouplingPathCount == 1)
        require(cognitiveLifecycleOwnerCount == 1)
        require(foundationModelRuntimeDependencyCount == 0)
    }
    override val invariantIds = setOf(
        "NO_FOUNDATION_MODEL_RUNTIME_DEPENDENCY",
        "BOOTENGINE_IS_SINGLE_LIFECYCLE_OWNER",
        "WORLD_FORMULA_IS_SINGLE_COGNITIVE_COUPLING_LAYER",
        "NO_SECOND_COGNITIVE_CONTROL_PLANE",
    )
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/authority-topology/v1", proofId, bootEngineOwnerId,
        productiveCouplingAuthorityId, productiveCouplingPathCount.toString(),
        cognitiveLifecycleOwnerCount.toString(), foundationModelRuntimeDependencyCount.toString(),
    )
}

data class WorldFormulaProof(
    override val proofId: String,
    val cycleId: String,
    val equationVersion: String,
    val productiveSnapshotId: String,
    val productiveSnapshotFingerprint: String,
    val provenanceFingerprint: String,
    val decisionCheckpointId: String,
    val decisionWorldSnapshotId: String,
    val equationStayedFrozenDuringCycle: Boolean,
    val truthScoreExposed: Boolean,
    val externalEffectAuthority: Boolean,
    val directSubsystemMutationObserved: Boolean,
) : Level7InvariantProof {
    init {
        listOf(proofId, cycleId, equationVersion, productiveSnapshotId,
            productiveSnapshotFingerprint, provenanceFingerprint, decisionCheckpointId,
            decisionWorldSnapshotId).forEach { require(it.isNotBlank()) }
        require(productiveSnapshotId == decisionWorldSnapshotId)
        require(equationStayedFrozenDuringCycle)
        require(!truthScoreExposed)
        require(!externalEffectAuthority)
        require(!directSubsystemMutationObserved)
    }
    override val invariantIds = setOf(
        "NO_DIRECT_SUBSYSTEM_WORLD_STATE_MUTATION",
        "NO_WORLD_FORMULA_SCALAR_TRUTH_SCORE",
        "NO_WORLD_FORMULA_EXTERNAL_EFFECT_AUTHORITY",
        "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT",
        "NO_WORLD_SNAPSHOT_WITHOUT_PROVENANCE",
        "NO_WORLD_EQUATION_CHANGE_DURING_ACTIVE_CYCLE",
        "NO_UNVERSIONED_WORLD_PHYSICS",
    )
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/world-formula/v1", proofId, cycleId, equationVersion,
        productiveSnapshotId, productiveSnapshotFingerprint, provenanceFingerprint,
        decisionCheckpointId, decisionWorldSnapshotId,
        equationStayedFrozenDuringCycle.toString(), truthScoreExposed.toString(),
        externalEffectAuthority.toString(), directSubsystemMutationObserved.toString(),
    )
}

data class EvolutionPromotionProof(
    override val proofId: String,
    val subjectId: String,
    val holdoutEvidenceId: String,
    val shadowEvidenceId: String,
    val trialEvidenceId: String,
    val promotionDecisionId: String,
    val moduleDirectPhysicsWriteObserved: Boolean,
    val metaAdaptedProtectedRoot: Boolean,
) : Level7InvariantProof {
    init {
        listOf(proofId, subjectId, holdoutEvidenceId, shadowEvidenceId, trialEvidenceId,
            promotionDecisionId).forEach { require(it.isNotBlank()) }
        require(!moduleDirectPhysicsWriteObserved)
        require(!metaAdaptedProtectedRoot)
    }
    override val invariantIds = setOf(
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_HOLDOUT",
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_SHADOW",
        "NO_WORLD_EQUATION_PROMOTION_WITHOUT_TRIAL",
        "NO_META_ADAPTATION_OF_PROTECTED_ROOT",
        "NO_MODULE_DIRECTLY_WRITES_WORLD_PHYSICS",
    )
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/evolution/v1", proofId, subjectId, holdoutEvidenceId,
        shadowEvidenceId, trialEvidenceId, promotionDecisionId,
        moduleDirectPhysicsWriteObserved.toString(), metaAdaptedProtectedRoot.toString(),
    )
}

data class CausalDiscriminationProof(
    override val proofId: String,
    val competingModelIds: Set<String>,
    val discriminationRequestId: String,
    val discriminatingEvidenceFingerprint: String,
    val selectedWorldModelId: String,
    val correlationOnly: Boolean,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(competingModelIds.size >= 2 && competingModelIds.none { it.isBlank() })
        require(discriminationRequestId.isNotBlank())
        require(discriminatingEvidenceFingerprint.isNotBlank())
        require(selectedWorldModelId in competingModelIds)
        require(!correlationOnly)
    }
    override val invariantIds = setOf("NO_CAUSAL_COEFFICIENT_FROM_CORRELATION_ONLY")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/causal-discrimination/v1", proofId, discriminationRequestId,
        discriminatingEvidenceFingerprint, selectedWorldModelId, correlationOnly.toString(),
        *competingModelIds.sorted().toTypedArray(),
    )
}

data class CounterfactualIsolationProof(
    override val proofId: String,
    val productiveSnapshotId: String,
    val counterfactualSnapshotId: String,
    val counterfactualNamespace: String,
    val productiveHeadUnchanged: Boolean,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(productiveSnapshotId.isNotBlank())
        require(counterfactualSnapshotId.isNotBlank() && counterfactualSnapshotId != productiveSnapshotId)
        require(counterfactualNamespace == "COUNTERFACTUAL")
        require(productiveHeadUnchanged)
    }
    override val invariantIds = setOf("NO_SIMULATION_SNAPSHOT_AS_PRODUCTIVE_WORLD_STATE")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/counterfactual/v1", proofId, productiveSnapshotId,
        counterfactualSnapshotId, counterfactualNamespace, productiveHeadUnchanged.toString(),
    )
}

data class StrategyReuseProof(
    override val proofId: String,
    val strategyCandidateId: String,
    val verifiedOutcomeIds: Set<String>,
    val unseenCaseId: String,
    val reuseDecisionId: String,
    val reusedSuccessfully: Boolean,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(strategyCandidateId.isNotBlank())
        require(verifiedOutcomeIds.size >= 2 && verifiedOutcomeIds.none { it.isBlank() })
        require(unseenCaseId.isNotBlank() && reuseDecisionId.isNotBlank())
        require(reusedSuccessfully)
    }
    override val invariantIds = setOf("NO_STRATEGY_PROMOTION_WITHOUT_VERIFIED_OUTCOME")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/strategy-reuse/v1", proofId, strategyCandidateId,
        unseenCaseId, reuseDecisionId, reusedSuccessfully.toString(),
        *verifiedOutcomeIds.sorted().toTypedArray(),
    )
}

data class GoalAuthorityProof(
    override val proofId: String,
    val generatedGoalId: String,
    val ownerAuthorityDecisionId: String,
    val activatedState: String,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank() && generatedGoalId.isNotBlank() && ownerAuthorityDecisionId.isNotBlank())
        require(activatedState == "ACTIVE")
    }
    override val invariantIds = setOf("NO_SYSTEM_GOAL_BYPASSES_OWNER_AUTHORITY")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/goal-authority/v1", proofId, generatedGoalId,
        ownerAuthorityDecisionId, activatedState,
    )
}

data class BoundedRetrievalProof(
    override val proofId: String,
    val architectureScanFingerprint: String,
    val productiveFullVaultScanViolations: List<String>,
    val maxObservedPageSize: Int,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank() && architectureScanFingerprint.isNotBlank())
        require(productiveFullVaultScanViolations.isEmpty())
        require(maxObservedPageSize in 1..256)
    }
    override val invariantIds = setOf("NO_PRODUCTIVE_FULL_VAULT_SCAN")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/bounded-retrieval/v1", proofId, architectureScanFingerprint,
        maxObservedPageSize.toString(),
    )
}

data class LearningDedupProof(
    override val proofId: String,
    val watermarkFingerprintBefore: String,
    val watermarkFingerprintAfterReplay: String,
    val logicalKeysBefore: Set<String>,
    val logicalKeysAfterReplay: Set<String>,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(watermarkFingerprintBefore.isNotBlank() && watermarkFingerprintAfterReplay.isNotBlank())
        require(logicalKeysBefore == logicalKeysAfterReplay)
    }
    override val invariantIds = setOf("NO_DUPLICATE_LOGICAL_LEARNING_AFTER_RESTART")
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/learning-dedup/v1", proofId, watermarkFingerprintBefore,
        watermarkFingerprintAfterReplay, *logicalKeysBefore.sorted().toTypedArray(),
    )
}

data class RecoveryProof(
    override val proofId: String,
    val planId: String,
    val preDeath: ProcessDeathSemanticCheckpoint,
    val postRehydration: ProcessDeathSemanticCheckpoint,
    val restoredExactEquationVersion: String,
    val restoredExactWorldHeadFingerprint: String,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank() && planId.isNotBlank())
        require(restoredExactEquationVersion == preDeath.equationVersion)
        require(restoredExactEquationVersion == postRehydration.equationVersion)
        require(restoredExactWorldHeadFingerprint == preDeath.worldHeadFingerprint)
        require(restoredExactWorldHeadFingerprint == postRehydration.worldHeadFingerprint)
        require(preDeath.decisionSemanticFingerprint == postRehydration.decisionSemanticFingerprint)
    }
    override val invariantIds = setOf(
        "ROLLBACK_RESTORES_EXACT_EQUATION_AND_WORLD_HEAD",
        "PROCESS_DEATH_MUST_NOT_CHANGE_DECISION_SEMANTICS",
    )
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/recovery/v1", proofId, planId, preDeath.fingerprint(),
        postRehydration.fingerprint(), restoredExactEquationVersion,
        restoredExactWorldHeadFingerprint,
    )
}

data class NovelDomainProof(
    override val proofId: String,
    val domainId: String,
    val staticDomainRulePresent: Boolean,
    val initialDecisionState: String,
    val abstractionCandidateId: String,
    val representationOrStrategyId: String,
    val unseenCaseId: String,
    val finalDecisionCheckpointId: String,
    val solvedUsingLearnedStructure: Boolean,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank() && domainId.isNotBlank())
        require(!staticDomainRulePresent)
        require(initialDecisionState == "UNRESOLVED")
        require(abstractionCandidateId.isNotBlank() && representationOrStrategyId.isNotBlank())
        require(unseenCaseId.isNotBlank() && finalDecisionCheckpointId.isNotBlank())
        require(solvedUsingLearnedStructure)
    }
    override val invariantIds: Set<String> = emptySet()
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/novel-domain/v1", proofId, domainId,
        staticDomainRulePresent.toString(), initialDecisionState,
        abstractionCandidateId, representationOrStrategyId, unseenCaseId,
        finalDecisionCheckpointId, solvedUsingLearnedStructure.toString(),
    )
}

data class TransferProof(
    override val proofId: String,
    val sourceDomainId: String,
    val targetDomainId: String,
    val semanticIdentityAssumed: Boolean,
    val structuralTransferCandidateId: String,
    val validationFingerprint: String,
    val adaptedStrategyId: String,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank() && sourceDomainId.isNotBlank() && targetDomainId.isNotBlank())
        require(sourceDomainId != targetDomainId)
        require(!semanticIdentityAssumed)
        require(structuralTransferCandidateId.isNotBlank())
        require(validationFingerprint.isNotBlank() && adaptedStrategyId.isNotBlank())
    }
    override val invariantIds: Set<String> = emptySet()
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/transfer/v1", proofId, sourceDomainId, targetDomainId,
        semanticIdentityAssumed.toString(), structuralTransferCandidateId,
        validationFingerprint, adaptedStrategyId,
    )
}

data class ProvenanceTraceProof(
    override val proofId: String,
    val traceIds: Set<String>,
    val revisionRefs: Set<String>,
    val provenanceFingerprints: Set<String>,
) : Level7InvariantProof {
    init {
        require(proofId.isNotBlank())
        require(traceIds.isNotEmpty() && traceIds.none { it.isBlank() })
        require(revisionRefs.isNotEmpty() && revisionRefs.none { it.isBlank() })
        require(provenanceFingerprints.isNotEmpty() && provenanceFingerprints.none { it.isBlank() })
    }
    override val invariantIds: Set<String> = emptySet()
    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-proof/provenance-trace/v1", proofId,
        *traceIds.sorted().toTypedArray(), *revisionRefs.sorted().toTypedArray(),
        *provenanceFingerprints.sorted().toTypedArray(),
    )
}

data class Level7GoldEvidence(
    val proofs: List<Level7InvariantProof>,
) {
    init {
        require(proofs.isNotEmpty())
        require(proofs.map { it.proofId }.distinct().size == proofs.size)
        require(proofs.map { it.fingerprint() }.distinct().size == proofs.size)
        val covered = proofs.flatMapTo(linkedSetOf()) { it.invariantIds }
        require(covered.containsAll(Level7GoldInvariants.REQUIRED)) {
            "Missing typed GOLD invariant proofs: ${Level7GoldInvariants.REQUIRED - covered}"
        }
        require(proofs.any { it is NovelDomainProof }) { "Functional GOLD requires NovelDomainProof" }
        require(proofs.any { it is TransferProof }) { "Functional GOLD requires TransferProof" }
        require(proofs.any { it is CausalDiscriminationProof }) { "Functional GOLD requires CausalDiscriminationProof" }
        require(proofs.any { it is StrategyReuseProof }) { "Functional GOLD requires StrategyReuseProof" }
        require(proofs.any { it is RecoveryProof }) { "Functional GOLD requires RecoveryProof" }
        require(proofs.any { it is ProvenanceTraceProof }) { "Functional GOLD requires ProvenanceTraceProof" }
    }
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-functional-gold-evidence/v2",
        *proofs.sortedBy { it.proofId }.map { it.fingerprint() }.toTypedArray(),
    )
}

object Level7GoldVerifier {
    fun verify(evidence: Level7GoldEvidence): String {
        val covered = evidence.proofs.flatMapTo(linkedSetOf()) { it.invariantIds }
        val missing = Level7GoldInvariants.REQUIRED - covered
        require(missing.isEmpty()) { "Missing typed GOLD invariants: $missing" }
        return "LEVEL7-FUNCTIONAL-GOLD:${evidence.fingerprint()}"
    }
}
