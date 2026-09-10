package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId

enum class ThoughtRelationType {
    DERIVED_FROM,
    SUPPORTS,
    CONTRADICTS,
    REFERENCES,
    TRANSFORMS,
}

data class ThoughtRelationSource(
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val origin: String,
) {
    init {
        require(sourceRevision > 0) { "Thought relation source revision must be positive" }
        require(origin.isNotBlank()) { "Thought relation origin must not be blank" }
    }
}

data class ThoughtRelation(
    val id: String,
    val sourcePhotonId: PhotonId,
    val targetPhotonId: PhotonId,
    val type: ThoughtRelationType,
    val weight: Double,
    val source: ThoughtRelationSource,
) {
    init {
        require(id.isNotBlank()) { "Thought relation id must not be blank" }
        require(weight in 0.0..1.0) { "Thought relation weight must be in 0..1" }
        require(source.sourcePhotonId == sourcePhotonId) {
            "Thought relation provenance must belong to the relation source photon"
        }
    }

    companion object {
        fun create(
            sourcePhotonId: PhotonId,
            targetPhotonId: PhotonId,
            type: ThoughtRelationType,
            weight: Double,
            sourceRevision: Long,
            origin: String,
        ): ThoughtRelation = ThoughtRelation(
            id = "thought-rel:" + StableFieldIds.fingerprint(
                sourcePhotonId.value,
                sourceRevision.toString(),
                targetPhotonId.value,
                type.name,
                weight.toString(),
                origin,
            ),
            sourcePhotonId = sourcePhotonId,
            targetPhotonId = targetPhotonId,
            type = type,
            weight = weight,
            source = ThoughtRelationSource(
                sourcePhotonId = sourcePhotonId,
                sourceRevision = sourceRevision,
                origin = origin,
            ),
        )
    }
}
