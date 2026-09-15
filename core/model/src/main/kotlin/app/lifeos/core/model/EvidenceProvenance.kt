package app.lifeos.core.model

enum class EvidenceOrigin { USER_ASSERTED, DIRECT_OBSERVATION, DOCUMENT_EXTRACTED, EXTERNAL_SOURCE, DERIVED, SELF_GENERATED }

data class EvidenceProvenance(
    val evidenceId: String,
    val origin: EvidenceOrigin,
    val sourceFingerprint: String,
    val independentSourceGroup: String,
    val generatedByArtifactRevisionId: String? = null,
) {
    init {
        require(evidenceId.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        require(independentSourceGroup.isNotBlank())
        if (origin == EvidenceOrigin.SELF_GENERATED) require(!generatedByArtifactRevisionId.isNullOrBlank())
    }
}

/** Anti-echo: generated material is context until independently validated; it cannot self-confirm. */
object EvidenceWeightPolicy {
    fun effectiveWeightMicros(provenance: EvidenceProvenance, requestedWeightMicros: Long): Long {
        require(requestedWeightMicros in 0..1_000_000L)
        return if (provenance.origin == EvidenceOrigin.SELF_GENERATED) minOf(requestedWeightMicros, 250_000L) else requestedWeightMicros
    }

    fun independentCount(evidence: Collection<EvidenceProvenance>): Int =
        evidence.filter { it.origin != EvidenceOrigin.SELF_GENERATED }.map { it.independentSourceGroup }.distinct().size
}

data class ConsolidatedMemory(
    val memoryId: String,
    val semanticFingerprint: String,
    val sourceEventIds: List<CognitiveEventId>,
    val sourceEvidenceIds: List<String>,
    val knowledgeState: KnowledgeState,
) {
    init {
        require(memoryId.isNotBlank() && semanticFingerprint.isNotBlank())
        require(sourceEventIds.isNotEmpty() && sourceEventIds.distinct().size == sourceEventIds.size)
        require(sourceEvidenceIds.distinct().size == sourceEvidenceIds.size)
    }
}

enum class ForgettingMode { CACHE_EVICTION, COGNITIVE_DEACTIVATION, SEMANTIC_COMPRESSION, RETENTION_EXPIRY, DATA_DELETION }

data class ForgettingDecision(
    val targetFingerprint: String,
    val mode: ForgettingMode,
    val reconstructable: Boolean,
    val preservesAuthoritativeEvidence: Boolean,
) {
    init {
        require(targetFingerprint.isNotBlank())
        if (mode in setOf(ForgettingMode.CACHE_EVICTION, ForgettingMode.COGNITIVE_DEACTIVATION, ForgettingMode.SEMANTIC_COMPRESSION)) require(reconstructable)
    }
}
