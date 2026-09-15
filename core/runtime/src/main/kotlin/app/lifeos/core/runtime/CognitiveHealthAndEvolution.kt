package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveTransactionId

enum class CognitiveHealthFailure {
    CONVERGENCE_OSCILLATION,
    CONTEXT_LOOP,
    MEMORY_ECHO,
    MODULE_DOMINANCE,
    CONFLICT_EXPLOSION,
    STALE_WORLD_PROJECTION,
    REPEATED_RETRIEVAL_FAILURE,
}

data class CognitiveHealthObservation(
    val failure: CognitiveHealthFailure,
    val transactionId: CognitiveTransactionId?,
    val evidenceFingerprints: Set<String>,
    val severityMicros: Long,
) {
    init { require(evidenceFingerprints.none { it.isBlank() }); require(severityMicros in 0..1_000_000L) }
}

data class CapabilityGapEvidence(
    val requiredCapabilityId: String,
    val attemptedModuleFingerprints: Set<String>,
    val failureEvidenceFingerprints: Set<String>,
) {
    init {
        require(requiredCapabilityId.isNotBlank())
        require(attemptedModuleFingerprints.isNotEmpty() && attemptedModuleFingerprints.none { it.isBlank() })
        require(failureEvidenceFingerprints.isNotEmpty() && failureEvidenceFingerprints.none { it.isBlank() })
    }
}

data class AutonomousCandidateGate(
    val minimalCapabilities: Set<String>,
    val sandboxPassed: Boolean,
    val testsPassed: Boolean,
    val shadowWorldPassed: Boolean,
    val invariantsPassed: Boolean,
    val signedCandidateVerified: Boolean,
) {
    init { require(minimalCapabilities.isNotEmpty() && minimalCapabilities.none { it.isBlank() }) }
    val promotable: Boolean get() = sandboxPassed && testsPassed && shadowWorldPassed && invariantsPassed && signedCandidateVerified
}

/** A new tool/module is justified only by explicit capability-gap evidence. */
class AutonomousToolWorkshopPolicy {
    fun mayPropose(gap: CapabilityGapEvidence): Boolean =
        gap.attemptedModuleFingerprints.isNotEmpty() && gap.failureEvidenceFingerprints.isNotEmpty()

    fun requirePromotable(gate: AutonomousCandidateGate) {
        require(gate.promotable) { "Autonomous candidate is not eligible for promotion" }
    }
}
