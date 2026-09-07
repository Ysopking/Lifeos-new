package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import java.time.Instant
import java.util.UUID

enum class PhotonDeltaType {
    CREATED,
    UPDATED,
    RELATION_ADDED,
    RELATION_REMOVED,
    CONFIDENCE_CHANGED,
    STATE_CHANGED,
    ACTIVATED,
    DEACTIVATED,
    INVALIDATED,
    OUTCOME_RECORDED,
    EXPIRED,
    QUARANTINED,
}

data class PhotonDelta(
    val deltaId: String = UUID.randomUUID().toString(),
    val source: String,
    val photonId: PhotonId? = null,
    val revisionBefore: Long? = null,
    val revisionAfter: Long? = null,
    val type: PhotonDeltaType,
    val importanceHint: Double? = null,
    val timestamp: Instant = Instant.now(),
    val causationId: String? = null,
    val correlationId: String? = null,
) {
    init {
        require(deltaId.isNotBlank()) { "Delta id must not be blank" }
        require(source.isNotBlank()) { "Delta source must not be blank" }
        require(revisionBefore == null || revisionBefore > 0) { "Previous revision must be positive" }
        require(revisionAfter == null || revisionAfter > 0) { "Next revision must be positive" }
        require(importanceHint == null || (importanceHint.isFinite() && importanceHint >= 0.0)) {
            "Importance hint must be finite and non-negative"
        }
    }
}

enum class CognitivePriority(val rank: Int) {
    IDLE(0),
    BACKGROUND(1),
    NORMAL(2),
    HIGH(3),
    USER_BLOCKING(4),
    CRITICAL(5),
}

data class CognitiveWorkBudget(
    val maxDurationMs: Long,
    val maxModuleInvocations: Int,
    val maxNewPhotons: Int,
    val maxNetworkCalls: Int,
) {
    init {
        require(maxDurationMs > 0) { "Duration budget must be positive" }
        require(maxModuleInvocations >= 0) { "Module invocation budget must not be negative" }
        require(maxNewPhotons >= 0) { "Photon budget must not be negative" }
        require(maxNetworkCalls >= 0) { "Network call budget must not be negative" }
    }
}

data class CognitiveWorkItem(
    val id: String = UUID.randomUUID().toString(),
    val triggeringDeltaId: String,
    val priority: CognitivePriority,
    val salience: Double,
    val enqueuedAt: Instant = Instant.now(),
    val targetModules: Set<String> = emptySet(),
    val budget: CognitiveWorkBudget,
    val photonId: PhotonId? = null,
    val photonRevision: Long? = null,
    val deltaType: PhotonDeltaType? = null,
) {
    init {
        require(id.isNotBlank()) { "Work id must not be blank" }
        require(triggeringDeltaId.isNotBlank()) { "Triggering delta id must not be blank" }
        require(salience.isFinite() && salience >= 0.0) { "Salience must be finite and non-negative" }
        require(photonRevision == null || photonRevision > 0) { "Photon revision must be positive" }
        require(photonRevision == null || photonId != null) {
            "Photon revision requires a photon id"
        }
        require(targetModules.none { it.isBlank() }) { "Target modules must not be blank" }
    }
}
