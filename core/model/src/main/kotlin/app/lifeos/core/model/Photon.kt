package app.lifeos.core.model

import java.time.Instant
import java.util.UUID

@JvmInline value class PhotonId(val value: String) {
    companion object { fun new(): PhotonId = PhotonId(UUID.randomUUID().toString()) }
}

enum class PhotonPhase { CREATED, ACTIVE, REFLECTING, CONVERGED, ARCHIVED }
enum class RelationType { DERIVED_FROM, SUPPORTS, CONTRADICTS, REFERENCES, TRANSFORMS }

data class Provenance(
    val source: String,
    val actor: String,
    val createdAt: Instant = Instant.now(),
    val parentIds: Set<PhotonId> = emptySet(),
)

data class PhotonRelation(val target: PhotonId, val type: RelationType, val weight: Double = 1.0) {
    init { require(weight in 0.0..1.0) }
}

data class Photon(
    val id: PhotonId = PhotonId.new(),
    val revision: Long = 1,
    val content: String,
    val mimeType: String = "text/plain",
    val phase: PhotonPhase = PhotonPhase.CREATED,
    val semanticMass: Double = 1.0,
    val energy: Double = 1.0,
    val confidence: Double = 1.0,
    val provenance: Provenance,
    val relations: Set<PhotonRelation> = emptySet(),
    val tags: Set<String> = emptySet(),
) {
    init {
        require(content.isNotBlank())
        require(revision > 0)
        require(semanticMass >= 0.0 && energy >= 0.0)
        require(confidence in 0.0..1.0)
    }
}

data class FieldInfluence(
    val module: String,
    val photonId: PhotonId,
    val type: String,
    val deltaEnergy: Double,
    val confidence: Double,
    val explanation: String,
    val occurredAt: Instant = Instant.now(),
)

interface PhotonStore {
    suspend fun save(photon: Photon)
    suspend fun loadAll(): List<Photon>
    suspend fun delete(id: PhotonId)
}
