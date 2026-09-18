package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class Level7Invariant {
    NO_FOUNDATION_MODEL_RUNTIME_DEPENDENCY,
    BOOTENGINE_IS_SINGLE_LIFECYCLE_OWNER,
    WORLD_FORMULA_IS_SINGLE_COGNITIVE_COUPLING_LAYER,
    NO_SECOND_COGNITIVE_CONTROL_PLANE,
    NO_DIRECT_SUBSYSTEM_WORLD_STATE_MUTATION,
    NO_WORLD_FORMULA_SCALAR_TRUTH_SCORE,
    NO_WORLD_FORMULA_EXTERNAL_EFFECT_AUTHORITY,
    NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT,
    NO_WORLD_SNAPSHOT_WITHOUT_PROVENANCE,
    NO_WORLD_EQUATION_CHANGE_DURING_ACTIVE_CYCLE,
    NO_WORLD_EQUATION_PROMOTION_WITHOUT_HOLDOUT,
    NO_WORLD_EQUATION_PROMOTION_WITHOUT_SHADOW,
    NO_WORLD_EQUATION_PROMOTION_WITHOUT_TRIAL,
    NO_CAUSAL_COEFFICIENT_FROM_CORRELATION_ONLY,
    NO_SIMULATION_SNAPSHOT_AS_PRODUCTIVE_WORLD_STATE,
    NO_STRATEGY_PROMOTION_WITHOUT_VERIFIED_OUTCOME,
    NO_META_ADAPTATION_OF_PROTECTED_ROOT,
    NO_MODULE_DIRECTLY_WRITES_WORLD_PHYSICS,
    NO_SYSTEM_GOAL_BYPASSES_OWNER_AUTHORITY,
    NO_PRODUCTIVE_FULL_VAULT_SCAN,
    NO_DUPLICATE_LOGICAL_LEARNING_AFTER_RESTART,
    NO_UNVERSIONED_WORLD_PHYSICS,
    ROLLBACK_RESTORES_EXACT_EQUATION_AND_WORLD_HEAD,
    PROCESS_DEATH_MUST_NOT_CHANGE_DECISION_SEMANTICS,
}

sealed interface Level7InvariantProof {
    val invariants: Set<Level7Invariant>
    fun fingerprint(): String
}

data class ArchitectureProof(
    val singleBootEngineOwner: Boolean,
    val singleWorldFormulaPath: Boolean,
    val noFoundationModelDependency: Boolean,
    val noSecondControlPlane: Boolean,
    val noProductiveFullVaultScan: Boolean,
    val sourceArchitectureFingerprint: String,
) : Level7InvariantProof {
    init {
        require(singleBootEngineOwner)
        require(singleWorldFormulaPath)
        require(noFoundationModelDependency)
        require(noSecondControlPlane)
        require(noProductiveFullVaultScan)
        require(sourceArchitectureFingerprint.isNotBlank())
    }

    override val invariants = setOf(
        Level7Invariant.NO_FOUNDATION_MODEL_RUNTIME_DEPENDENCY,
        Level7Invariant.BOOTENGINE_IS_SINGLE_LIFECYCLE_OWNER,
        Level7Invariant.WORLD_FORMULA_IS_SINGLE_COGNITIVE_COUPLING_LAYER,
        Level7Invariant.NO_SECOND_COGNITIVE_CONTROL_PLANE,
        Level7Invariant.NO_PRODUCTIVE_FULL_VAULT_SCAN,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-architecture-proof/v2",
        sourceArchitectureFingerprint,
    )
}

data class WorldFormulaProof(
    val worldSnapshotId: String,
    val worldSnapshotFingerprint: String,
    val provenanceFingerprint: String,
    val equationVersion: String,
    val cycleFingerprint: String,
    val convergenceCheckpointId: String,
    val noScalarTruthScore: Boolean,
    val noExternalEffectAuthority: Boolean,
    val noDirectMutation: Boolean,
) : Level7InvariantProof {
    init {
        listOf(
            worldSnapshotId,
            worldSnapshotFingerprint,
            provenanceFingerprint,
            equationVersion,
            cycleFingerprint,
            convergenceCheckpointId,
        ).forEach { require(it.isNotBlank()) }
        require(noScalarTruthScore && noExternalEffectAuthority && noDirectMutation)
    }

    override val invariants = setOf(
        Level7Invariant.NO_DIRECT_SUBSYSTEM_WORLD_STATE_MUTATION,
        Level7Invariant.NO_WORLD_FORMULA_SCALAR_TRUTH_SCORE,
        Level7Invariant.NO_WORLD_FORMULA_EXTERNAL_EFFECT_AUTHORITY,
        Level7Invariant.NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT,
        Level7Invariant.NO_WORLD_SNAPSHOT_WITHOUT_PROVENANCE,
        Level7Invariant.NO_WORLD_EQUATION_CHANGE_DURING_ACTIVE_CYCLE,
        Level7Invariant.NO_UNVERSIONED_WORLD_PHYSICS,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-world-formula-proof/v2",
        worldSnapshotId,
        worldSnapshotFingerprint,
        provenanceFingerprint,
        equationVersion,
        cycleFingerprint,
        convergenceCheckpointId,
    )
}

data class PromotionChainProof(
    val holdoutFingerprint: String,
    val shadowFingerprint: String,
    val trialFingerprint: String,
    val promotionFingerprint: String,
    val moduleDirectWriteBlocked: Boolean,
) : Level7InvariantProof {
    init {
        listOf(
            holdoutFingerprint,
            shadowFingerprint,
            trialFingerprint,
            promotionFingerprint,
        ).forEach { require(it.isNotBlank()) }
        require(moduleDirectWriteBlocked)
    }

    override val invariants = setOf(
        Level7Invariant.NO_WORLD_EQUATION_PROMOTION_WITHOUT_HOLDOUT,
        Level7Invariant.NO_WORLD_EQUATION_PROMOTION_WITHOUT_SHADOW,
        Level7Invariant.NO_WORLD_EQUATION_PROMOTION_WITHOUT_TRIAL,
        Level7Invariant.NO_MODULE_DIRECTLY_WRITES_WORLD_PHYSICS,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-promotion-chain-proof/v2",
        holdoutFingerprint,
        shadowFingerprint,
        trialFingerprint,
        promotionFingerprint,
    )
}

data class CausalDiscriminationProof(
    val competingCandidateIds: Set<String>,
    val discriminationRequestFingerprint: String,
    val correlationOnlyRejected: Boolean,
) : Level7InvariantProof {
    init {
        require(competingCandidateIds.size >= 2)
        require(discriminationRequestFingerprint.isNotBlank())
        require(correlationOnlyRejected)
    }

    override val invariants = setOf(
        Level7Invariant.NO_CAUSAL_COEFFICIENT_FROM_CORRELATION_ONLY,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-causal-discrimination-proof/v2",
        discriminationRequestFingerprint,
        *competingCandidateIds.sorted().toTypedArray(),
    )
}

data class StrategyReuseProof(
    val strategyCandidateId: String,
    val verifiedTransitionFingerprints: Set<String>,
    val reuseOutcomeFingerprint: String,
) : Level7InvariantProof {
    init {
        require(strategyCandidateId.isNotBlank())
        require(verifiedTransitionFingerprints.size >= 2)
        require(reuseOutcomeFingerprint.isNotBlank())
    }

    override val invariants = setOf(
        Level7Invariant.NO_STRATEGY_PROMOTION_WITHOUT_VERIFIED_OUTCOME,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-strategy-reuse-proof/v2",
        strategyCandidateId,
        reuseOutcomeFingerprint,
        *verifiedTransitionFingerprints.sorted().toTypedArray(),
    )
}

data class ProtectedRootProof(
    val blockedComponent: ProtectedRootComponent,
    val mutationTargetPath: String,
    val metaAdaptationBlocked: Boolean,
    val systemGoalAuthorityRequired: Boolean,
) : Level7InvariantProof {
    init {
        require(mutationTargetPath.isNotBlank())
        require(metaAdaptationBlocked)
        require(systemGoalAuthorityRequired)
    }

    override val invariants = setOf(
        Level7Invariant.NO_META_ADAPTATION_OF_PROTECTED_ROOT,
        Level7Invariant.NO_SYSTEM_GOAL_BYPASSES_OWNER_AUTHORITY,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-protected-root-proof/v2",
        blockedComponent.name,
        mutationTargetPath,
    )
}

data class CounterfactualProof(
    val counterfactualSnapshotId: String,
    val productiveHeadBefore: String,
    val productiveHeadAfter: String,
) : Level7InvariantProof {
    init {
        require(counterfactualSnapshotId.isNotBlank())
        require(productiveHeadBefore.isNotBlank())
        require(productiveHeadAfter.isNotBlank())
        require(productiveHeadBefore == productiveHeadAfter)
    }

    override val invariants = setOf(
        Level7Invariant.NO_SIMULATION_SNAPSHOT_AS_PRODUCTIVE_WORLD_STATE,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-counterfactual-proof/v2",
        counterfactualSnapshotId,
        productiveHeadBefore,
    )
}

data class LearningDedupProof(
    val logicalKeysBefore: Set<String>,
    val logicalKeysAfter: Set<String>,
    val watermarkFingerprint: String,
) : Level7InvariantProof {
    init {
        require(logicalKeysBefore == logicalKeysAfter)
        require(watermarkFingerprint.isNotBlank())
    }

    override val invariants = setOf(
        Level7Invariant.NO_DUPLICATE_LOGICAL_LEARNING_AFTER_RESTART,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-learning-dedup-proof/v2",
        watermarkFingerprint,
        *logicalKeysBefore.sorted().toTypedArray(),
    )
}

data class RecoveryProof(
    val preDeath: ProcessDeathSemanticCheckpoint,
    val postRehydration: ProcessDeathSemanticCheckpoint,
) : Level7InvariantProof {
    init {
        require(preDeath.worldHeadFingerprint == postRehydration.worldHeadFingerprint)
        require(preDeath.equationVersion == postRehydration.equationVersion)
        require(preDeath.decisionSemanticFingerprint == postRehydration.decisionSemanticFingerprint)
    }

    override val invariants = setOf(
        Level7Invariant.PROCESS_DEATH_MUST_NOT_CHANGE_DECISION_SEMANTICS,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-recovery-proof/v2",
        preDeath.fingerprint(),
        postRehydration.fingerprint(),
    )
}

data class RollbackProof(
    val degradedVersion: String,
    val restoredEquationVersion: String,
    val expectedEquationVersion: String,
    val restoredWorldHeadFingerprint: String,
    val expectedWorldHeadFingerprint: String,
) : Level7InvariantProof {
    init {
        require(degradedVersion.isNotBlank())
        require(restoredEquationVersion == expectedEquationVersion)
        require(restoredWorldHeadFingerprint == expectedWorldHeadFingerprint)
    }

    override val invariants = setOf(
        Level7Invariant.ROLLBACK_RESTORES_EXACT_EQUATION_AND_WORLD_HEAD,
    )

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-rollback-proof/v2",
        degradedVersion,
        restoredEquationVersion,
        restoredWorldHeadFingerprint,
    )
}

data class NovelDomainProof(
    val domainId: String,
    val staticDomainRulePresent: Boolean,
    val beforeLearningUnresolved: Boolean,
    val abstractionCandidateId: String,
    val learnedRepresentationFingerprint: String,
    val unseenCaseOutcomeFingerprint: String,
) : Level7InvariantProof {
    init {
        require(domainId.isNotBlank())
        require(!staticDomainRulePresent)
        require(beforeLearningUnresolved)
        require(abstractionCandidateId.isNotBlank())
        require(learnedRepresentationFingerprint.isNotBlank())
        require(unseenCaseOutcomeFingerprint.isNotBlank())
    }

    override val invariants: Set<Level7Invariant> = emptySet()

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-novel-domain-proof/v2",
        domainId,
        abstractionCandidateId,
        learnedRepresentationFingerprint,
        unseenCaseOutcomeFingerprint,
    )
}

data class TransferProof(
    val sourceDomainId: String,
    val targetDomainId: String,
    val semanticIdentityAssumed: Boolean,
    val structuralTransferCandidateId: String,
    val validationFingerprint: String,
    val adaptedOutcomeFingerprint: String,
) : Level7InvariantProof {
    init {
        require(sourceDomainId.isNotBlank() && targetDomainId.isNotBlank())
        require(sourceDomainId != targetDomainId)
        require(!semanticIdentityAssumed)
        require(structuralTransferCandidateId.isNotBlank())
        require(validationFingerprint.isNotBlank())
        require(adaptedOutcomeFingerprint.isNotBlank())
    }

    override val invariants: Set<Level7Invariant> = emptySet()

    override fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-transfer-proof/v2",
        sourceDomainId,
        targetDomainId,
        structuralTransferCandidateId,
        validationFingerprint,
        adaptedOutcomeFingerprint,
    )
}

data class Level7GoldEvidence(
    val proofs: List<Level7InvariantProof>,
) {
    init {
        require(proofs.isNotEmpty())
        require(proofs.map { it.fingerprint() }.distinct().size == proofs.size)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-gold-evidence/v2",
        *proofs.map { it.fingerprint() }.sorted().toTypedArray(),
    )
}

object Level7GoldVerifier {
    val required: Set<Level7Invariant> = Level7Invariant.entries.toSet()

    fun verify(evidence: Level7GoldEvidence): String {
        val proven = evidence.proofs.flatMapTo(linkedSetOf()) { it.invariants }
        val missing = required - proven
        require(missing.isEmpty()) { "Missing typed GOLD proofs: $missing" }
        require(evidence.proofs.any { it is NovelDomainProof }) {
            "Functional GOLD requires NovelDomainProof"
        }
        require(evidence.proofs.any { it is TransferProof }) {
            "Functional GOLD requires TransferProof"
        }
        require(evidence.proofs.any { it is CausalDiscriminationProof }) {
            "Functional GOLD requires CausalDiscriminationProof"
        }
        require(evidence.proofs.any { it is StrategyReuseProof }) {
            "Functional GOLD requires StrategyReuseProof"
        }
        require(evidence.proofs.any { it is RecoveryProof }) {
            "Functional GOLD requires RecoveryProof"
        }
        require(evidence.proofs.any { it is RollbackProof }) {
            "Functional GOLD requires RollbackProof"
        }
        return "LEVEL7-GOLD:${evidence.fingerprint()}"
    }
}
