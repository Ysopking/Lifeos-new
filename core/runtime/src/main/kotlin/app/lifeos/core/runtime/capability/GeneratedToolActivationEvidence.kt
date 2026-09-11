package app.lifeos.core.runtime.capability

/**
 * Sealed, non-authoritative activation evidence boundary for generated tools.
 *
 * Evidence may bind an exact TRIAL record, exact trial evidence and exact promotion policy, but it
 * never grants activation by itself. Activation remains an internal lifecycle mutation guarded by
 * the current runtime state and capability-registration invariants.
 */
sealed interface GeneratedToolActivationEvidence {
    val id: String
    val toolId: String
    val recordFingerprint: String
    val trialEvidenceId: String
    val promotionPolicyFingerprint: String
    val activationAllowed: Boolean
}

internal fun GeneratedToolActivationEvidence.matchesRecord(record: GeneratedToolRecord): Boolean =
    toolId == record.manifest.toolId && recordFingerprint == record.promotionRecordFingerprint()

internal fun GeneratedToolActivationEvidence.matches(
    record: GeneratedToolRecord,
    trialEvidence: GeneratedToolTrialEvidence,
    policy: GeneratedToolPromotionPolicy,
): Boolean =
    matchesRecord(record) &&
        trialEvidenceId == trialEvidence.id &&
        promotionPolicyFingerprint == policy.fingerprint()

internal fun GeneratedToolActivationEvidence.promotionAuditReason(): String = when (this) {
    is GeneratedToolPromotionEvidence -> "j03-promotion-evidence:$id"
    is BoundedGeneratedToolPromotionEvidence -> "bounded-generated-tool-promotion-evidence:$id"
}
