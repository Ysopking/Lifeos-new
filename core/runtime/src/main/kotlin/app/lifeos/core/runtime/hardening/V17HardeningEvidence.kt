package app.lifeos.core.runtime.hardening

enum class V17CrashBoundary {
    GOAL_PERSISTENCE,
    CONVERGENCE_PERSISTENCE,
    OWNER_POLICY_PERSISTENCE,
    RESOURCE_RESERVATION,
    RESOURCE_OUTCOME,
    RESOURCE_SETTLEMENT,
    SELF_HEALING_PERSISTENCE,
    EVOLUTION_PERSISTENCE,
    HOT_SWAP_PREPARE,
    HOT_SWAP_EXPOSE,
    TOOL_WORKSHOP_PERSISTENCE,
    DEEP_SEARCH_CHECKPOINT,
    ARTIFACT_FINALIZATION,
}

enum class V17CrashWindow {
    BEFORE_BOUNDARY,
    AFTER_BOUNDARY,
}

enum class V17CorruptionCase {
    VALID_CURRENT_CODEC,
    TRUNCATION,
    UNSUPPORTED_VERSION,
    AUTHENTICATED_CIPHERTEXT_CORRUPTION,
    OVERSIZED_PAYLOAD,
    PARTIAL_READABLE,
    PARTIAL_UNREADABLE,
    PREVIOUS_SCHEMA_MIGRATION,
}

enum class V17OwnerRevocationWindow {
    BEFORE_PREPARE,
    AFTER_PREPARE_BEFORE_EXPOSE,
    ACROSS_RESTART,
    UNREADABLE_POLICY_STORE,
    POLICY_HISTORY_NO_DEFAULT_RECREATION,
}

enum class V17EnduranceScenario {
    ORPHAN_RESERVATION,
    DURABLE_OUTCOME_BEFORE_SETTLEMENT,
    THERMAL_PRESSURE,
    BATTERY_PRESSURE,
    STORAGE_PRESSURE,
    MEMORY_PRESSURE,
    FOREGROUND_BACKGROUND,
    REPEATED_RESTART,
    SOAK,
}

enum class V17Journey {
    GOAL_TO_EXPLANATION,
    GAP_TOOLWORKSHOP_TO_COMPLETION,
    PROVIDER_REGRESSION_TO_RECOVERY,
    DEEPSEARCH_ARTIFACT_TO_COGNITION,
}

data class V17AcceptanceEvidence(
    val candidateSha: String,
    val scenarioId: String,
    val environment: String,
    val runId: String,
    val observedDurableState: String,
    val duplicateEffectCount: Long,
    val traceIds: List<String> = emptyList(),
    val policyIds: List<String> = emptyList(),
    val resourceIds: List<String> = emptyList(),
    val outcomeIds: List<String> = emptyList(),
    val apkSha256: String? = null,
) {
    init {
        require(candidateSha.matches(Regex("[0-9a-f]{40,64}"))) { "Candidate SHA must be hex" }
        require(scenarioId.isNotBlank())
        require(environment.isNotBlank())
        require(runId.isNotBlank())
        require(observedDurableState.isNotBlank())
        require(duplicateEffectCount >= 0L)
        require(traceIds.none { it.isBlank() })
        require(policyIds.none { it.isBlank() })
        require(resourceIds.none { it.isBlank() })
        require(outcomeIds.none { it.isBlank() })
        require(apkSha256 == null || apkSha256.matches(Regex("[0-9a-f]{64}")))
    }

    val passesExactlyOnce: Boolean
        get() = duplicateEffectCount == 0L
}

/**
 * V17 intentionally does not turn evidence collection into production authority.
 * It only rejects incomplete/unsafe proof records.
 */
object V17AcceptanceGate {
    fun requirePass(evidence: V17AcceptanceEvidence): V17AcceptanceEvidence {
        require(evidence.passesExactlyOnce) {
            "V17 evidence records duplicate productive effects"
        }
        return evidence
    }

    fun requireCompleteJourneyEvidence(
        journey: V17Journey,
        evidence: V17AcceptanceEvidence,
    ): V17AcceptanceEvidence {
        requirePass(evidence)
        require(evidence.traceIds.isNotEmpty()) { "$journey requires DecisionTrace evidence" }
        require(evidence.resourceIds.isNotEmpty()) { "$journey requires resource evidence" }
        require(evidence.outcomeIds.isNotEmpty()) { "$journey requires outcome evidence" }
        return evidence
    }
}
