package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant

enum class EvidenceKind {
    OBSERVATION,
    ASSERTION,
    DOCUMENT_FACT,
    TRANSACTION,
    MESSAGE_EVENT,
    NORM_SOURCE,
    SCIENTIFIC_RESULT,
    DERIVED_MEASUREMENT,
    USER_CORRECTION,
}

enum class SourceAuthority(val defaultWeight: Double) {
    UNVERIFIED(0.35),
    USER_PROVIDED(0.60),
    DOCUMENTED(0.72),
    PRIMARY_SOURCE(0.82),
    OFFICIAL(0.92),
    AUTHORITATIVE(1.0),
}

data class EvidenceReliability(
    val score: Double,
    val reason: String,
) {
    init {
        require(score in 0.0..1.0) { "Reliability score must be in 0..1" }
        require(reason.isNotBlank()) { "Reliability reason must not be blank" }
    }
}

data class TemporalValidity(
    val validFrom: Instant? = null,
    val validUntilExclusive: Instant? = null,
) {
    init {
        require(validFrom == null || validUntilExclusive == null || validFrom < validUntilExclusive) {
            "Validity start must be before exclusive end"
        }
    }

    fun contains(instant: Instant): Boolean =
        (validFrom == null || instant >= validFrom) &&
            (validUntilExclusive == null || instant < validUntilExclusive)

    fun overlap(other: TemporalValidity): Boolean {
        val start = listOfNotNull(validFrom, other.validFrom).maxOrNull()
        val end = listOfNotNull(validUntilExclusive, other.validUntilExclusive).minOrNull()
        return start == null || end == null || start < end
    }

    companion object {
        val UNBOUNDED = TemporalValidity()
        fun at(instant: Instant): TemporalValidity = TemporalValidity(instant, instant.plusNanos(1))
    }
}

data class EvidencePayload(
    val type: String,
    val values: Map<String, String>,
) {
    init {
        require(type.isNotBlank()) { "Evidence payload type must not be blank" }
        require(values.keys.none { it.isBlank() }) { "Evidence payload keys must not be blank" }
    }

    fun stableFingerprint(): String = StableFieldIds.fingerprint(
        type,
        *values.toSortedMap().flatMap { (key, value) -> listOf(key, value) }.toTypedArray(),
    )

    companion object {
        fun text(value: String): EvidencePayload = EvidencePayload(
            type = "text",
            values = mapOf("value" to value),
        )
    }
}

data class FieldEvidence(
    val id: EvidenceId,
    val domainId: FieldDomainId,
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val kind: EvidenceKind,
    val semanticKey: String,
    val confidence: Double,
    val reliability: EvidenceReliability,
    val authority: SourceAuthority,
    val observedAt: Instant,
    val validity: TemporalValidity = TemporalValidity.UNBOUNDED,
    val payload: EvidencePayload,
    val explanation: String,
) {
    init {
        require(sourceRevision > 0) { "Source revision must be positive" }
        require(semanticKey.isNotBlank()) { "Evidence semantic key must not be blank" }
        require(confidence in 0.0..1.0) { "Evidence confidence must be in 0..1" }
        require(explanation.isNotBlank()) { "Evidence explanation must not be blank" }
    }

    val sourceFingerprint: String
        get() = StableFieldIds.fingerprint(
            domainId.value,
            sourcePhotonId.value,
            sourceRevision.toString(),
            kind.name,
            semanticKey,
            observedAt.toString(),
            validity.validFrom?.toString().orEmpty(),
            validity.validUntilExclusive?.toString().orEmpty(),
            payload.stableFingerprint(),
        )

    companion object {
        fun create(
            domainId: FieldDomainId,
            sourcePhotonId: PhotonId,
            sourceRevision: Long,
            kind: EvidenceKind,
            semanticKey: String,
            confidence: Double,
            reliability: EvidenceReliability,
            authority: SourceAuthority,
            observedAt: Instant,
            validity: TemporalValidity = TemporalValidity.UNBOUNDED,
            payload: EvidencePayload,
            explanation: String,
            ordinal: Int = 0,
        ): FieldEvidence = FieldEvidence(
            id = StableFieldIds.evidence(
                domain = domainId,
                sourcePhotonId = sourcePhotonId.value,
                sourceRevision = sourceRevision,
                semanticKey = "$semanticKey:${payload.stableFingerprint()}",
                ordinal = ordinal,
            ),
            domainId = domainId,
            sourcePhotonId = sourcePhotonId,
            sourceRevision = sourceRevision,
            kind = kind,
            semanticKey = semanticKey,
            confidence = confidence,
            reliability = reliability,
            authority = authority,
            observedAt = observedAt,
            validity = validity,
            payload = payload,
            explanation = explanation,
        )
    }
}

enum class EvidenceRelationType {
    SUPPORTS,
    CONTRADICTS,
    REFINES,
    DERIVED_FROM,
    DUPLICATES,
}

data class EvidenceRelation(
    val source: EvidenceId,
    val target: EvidenceId,
    val type: EvidenceRelationType,
    val weight: Double,
    val explanation: String,
) {
    init {
        require(source != target) { "Evidence relation cannot point to itself" }
        require(weight in 0.0..1.0) { "Evidence relation weight must be in 0..1" }
        require(explanation.isNotBlank()) { "Evidence relation explanation must not be blank" }
    }
}

fun List<FieldEvidence>.stableEvidenceOrder(): List<FieldEvidence> =
    sortedWith(
        compareBy<FieldEvidence> { it.observedAt }
            .thenBy { it.sourcePhotonId.value }
            .thenBy { it.sourceRevision }
            .thenBy { it.id.value },
    )
