package app.lifeos.core.runtime.thought

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.PhotonId
import java.time.Instant

enum class ThoughtLifecycleStatus {
    ACTIVE,
    REFLECTING,
    CONVERGED,
    ARCHIVED,
}

enum class ThoughtVerificationStatus {
    UNVERIFIED,
    OBSERVED,
    VERIFIED,
    CONFLICTED,
}

data class ThoughtProvenance(
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val sourceFingerprint: String,
    val source: String,
    val actor: String,
    val createdAt: Instant,
) {
    init {
        require(sourceRevision > 0) { "Thought source revision must be positive" }
        require(sourceFingerprint.isNotBlank()) { "Thought source fingerprint must not be blank" }
        require(source.isNotBlank()) { "Thought provenance source must not be blank" }
        require(actor.isNotBlank()) { "Thought provenance actor must not be blank" }
    }
}

data class ThoughtNode(
    val provenance: ThoughtProvenance,
    val fieldDomainId: FieldDomainId,
    val semanticKey: String,
    val summary: String,
    val semanticMass: Double,
    val energy: Double,
    val confidence: Double,
    val validity: TemporalValidity,
    val lifecycle: ThoughtLifecycleStatus,
    val verification: ThoughtVerificationStatus,
    val tags: Set<String>,
) {
    init {
        require(semanticKey.isNotBlank()) { "Thought semantic key must not be blank" }
        require(summary.isNotBlank()) { "Thought summary must not be blank" }
        require(semanticMass.isFinite() && semanticMass >= 0.0) {
            "Thought semantic mass must be finite and non-negative"
        }
        require(energy.isFinite() && energy >= 0.0) {
            "Thought energy must be finite and non-negative"
        }
        require(confidence in 0.0..1.0) { "Thought confidence must be in 0..1" }
        require(tags.none { it.isBlank() }) { "Thought tags must not be blank" }
    }

    val photonId: PhotonId
        get() = provenance.sourcePhotonId

    val sourceRevision: Long
        get() = provenance.sourceRevision
}
