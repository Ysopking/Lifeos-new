package app.lifeos.core.language

import app.lifeos.core.model.PhotonId

enum class EvidenceOrigin {
    USER_ASSERTED,
    SOURCE_OBSERVED,
    DOCUMENT_EXTRACTED,
    EXTERNAL_VERIFIED,
    SYSTEM_DERIVED,
    SYSTEM_GENERATED;

    val isIndependentEvidence: Boolean
        get() = this !in setOf(SYSTEM_DERIVED, SYSTEM_GENERATED)
}

enum class ClaimVerificationState { UNVERIFIED, VERIFIED, CONFLICTED, OUTDATED, UNRESOLVED }

data class EvidenceBinding(
    val photonId: PhotonId,
    val revision: Long,
    val origin: EvidenceOrigin,
    val confidence: Double,
) {
    init { require(revision > 0); require(confidence in 0.0..1.0) }
}

data class GeneratedClaim(
    val text: String,
    val evidence: List<EvidenceBinding>,
    val confidence: Double,
    val verificationState: ClaimVerificationState,
) {
    init {
        require(text.isNotBlank())
        require(confidence in 0.0..1.0)
    }

    val independentEvidence: List<EvidenceBinding>
        get() = evidence.filter { it.origin.isIndependentEvidence }

    /** Hard anti-echo gate: generated/derived material can provide context, never independent corroboration. */
    fun hasIndependentSupport(): Boolean = independentEvidence.isNotEmpty()
}

data class SemanticResponsePlan(
    val claims: List<GeneratedClaim>,
    val requestedLanguage: LanguageCode,
) {
    /** Surface generators receive a closed claim set and may realize wording, not introduce new factual claims. */
    fun factualTexts(): List<String> = claims.map { it.text }
}
