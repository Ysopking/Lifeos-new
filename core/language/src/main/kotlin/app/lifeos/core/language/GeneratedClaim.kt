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
    /**
     * Bind observations known to originate from the same underlying evidence source. Independent
     * modules extracting the same document/fact must reuse this group instead of multiplying support.
     */
    val correlationGroup: String = photonId.value,
    /**
     * Epistemic source-independence, not policy authority. Generated/derived material is forced to 0.
     */
    val sourceIndependenceMicros: Long =
        if (origin.isIndependentEvidence) 1_000_000L else 0L,
) {
    init {
        require(revision > 0)
        require(confidence in 0.0..1.0)
        require(correlationGroup.isNotBlank())
        require(sourceIndependenceMicros in 0L..1_000_000L)
        if (!origin.isIndependentEvidence) {
            require(sourceIndependenceMicros == 0L) {
                "Generated/derived evidence cannot claim source independence"
            }
        }
    }

    val effectiveSupportMicros: Long
        get() = (
            confidence * sourceIndependenceMicros.toDouble()
            ).toLong().coerceIn(0L, 1_000_000L)
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

    /**
     * Correlated observations count once. Within one correlation group the strongest independent
     * binding represents the group; eight modules extracting one source never become eight sources.
     */
    val independentEvidence: List<EvidenceBinding>
        get() = evidence
            .filter { it.origin.isIndependentEvidence && it.sourceIndependenceMicros > 0L }
            .groupBy { it.correlationGroup }
            .values
            .map { group ->
                group.maxWithOrNull(
                    compareBy<EvidenceBinding> { it.effectiveSupportMicros }
                        .thenBy { it.confidence }
                        .thenBy { it.revision }
                        .thenBy { it.photonId.value }
                ) ?: error("Empty evidence correlation group")
            }
            .sortedWith(
                compareBy<EvidenceBinding> { it.correlationGroup }
                    .thenBy { it.photonId.value }
                    .thenBy { it.revision }
            )

    val independentCorrelationGroups: Set<String>
        get() = independentEvidence.mapTo(linkedSetOf()) { it.correlationGroup }

    /**
     * Bounded corroboration strength for ranking/utility only. This is not authorization and does
     * not replace verificationState; it merely prevents correlated semantic-mass inflation.
     */
    val effectiveIndependentSupportMicros: Long
        get() = independentEvidence.fold(0L) { total, binding ->
            (total + binding.effectiveSupportMicros).coerceAtMost(1_000_000L)
        }

    /** Hard anti-echo gate: generated/derived material can provide context, never independent corroboration. */
    fun hasIndependentSupport(): Boolean = independentEvidence.isNotEmpty()
}

data class SemanticResponsePlan(
    val claims: List<GeneratedClaim>,
    val requestedLanguage: LanguageCode,
) {
    /** Surface generators receive a closed claim set and may realize wording, not introduce new factual claims. */
    fun factualTexts(): List<String> = claims.map { it.text }

    val independentEvidenceGroups: Set<String>
        get() = claims.flatMapTo(linkedSetOf()) { it.independentCorrelationGroups }
}
