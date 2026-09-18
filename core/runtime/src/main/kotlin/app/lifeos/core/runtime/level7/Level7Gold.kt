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

data class Level7GoldEvidence(
    val invariantEvidence: Map<String, String>,
    val preDeath: ProcessDeathSemanticCheckpoint,
    val postRehydration: ProcessDeathSemanticCheckpoint,
    val logicalLearningKeysBefore: Set<String>,
    val logicalLearningKeysAfter: Set<String>,
) {
    init {
        require(invariantEvidence.keys.containsAll(Level7GoldInvariants.REQUIRED))
        require(invariantEvidence.values.none { it.isBlank() })
        require(logicalLearningKeysBefore == logicalLearningKeysAfter) {
            "Restart introduced duplicate or missing logical learning"
        }
        require(preDeath.decisionSemanticFingerprint == postRehydration.decisionSemanticFingerprint) {
            "Process death changed decision semantics"
        }
        require(preDeath.worldHeadFingerprint == postRehydration.worldHeadFingerprint) {
            "Rehydration did not restore exact productive world head"
        }
        require(preDeath.equationVersion == postRehydration.equationVersion) {
            "Rehydration did not restore exact world equation version"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-gold-evidence/v1",
        preDeath.fingerprint(),
        postRehydration.fingerprint(),
        *invariantEvidence.toSortedMap().flatMap { (key, value) -> listOf(key, value) }.toTypedArray(),
        *logicalLearningKeysBefore.sorted().toTypedArray(),
    )
}

object Level7GoldVerifier {
    fun verify(evidence: Level7GoldEvidence): String {
        val missing = Level7GoldInvariants.REQUIRED - evidence.invariantEvidence.keys
        require(missing.isEmpty()) { "Missing GOLD invariants: $missing" }
        return "LEVEL7-GOLD:${evidence.fingerprint()}"
    }
}
