package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.health.HealthNodeId

enum class FieldShadowState {
    COMPLETED,
    BLOCKED,
    FAILED,
}

data class FieldShadowExecution(
    val state: FieldShadowState,
    val domainId: FieldDomainId? = null,
    val runId: FieldRunId? = null,
    val snapshotId: FieldSnapshotId? = null,
    val convergenceStatus: ConvergenceStatus? = null,
    val message: String? = null,
    val sourcePhotonId: PhotonId? = null,
    val sourceRevision: Long? = null,
    val sourceFingerprint: String? = null,
) {
    init {
        if (state == FieldShadowState.COMPLETED) {
            require(domainId != null) { "Completed field shadow execution requires a domain" }
            require(runId != null) { "Completed field shadow execution requires a run id" }
            require(snapshotId != null) { "Completed field shadow execution requires a snapshot id" }
            require(convergenceStatus != null) {
                "Completed field shadow execution requires a convergence status"
            }
        }
        if (state != FieldShadowState.COMPLETED) {
            require(runId == null && snapshotId == null && convergenceStatus == null) {
                "Non-completed field shadow execution cannot expose committed result ids"
            }
        }
        val sourceParts = listOf(
            sourcePhotonId != null,
            sourceRevision != null,
            sourceFingerprint != null,
        ).count { it }
        require(sourceParts == 0 || sourceParts == 3) {
            "Field shadow source identity must be completely present or completely absent"
        }
        require(sourceRevision == null || sourceRevision > 0) {
            "Field shadow source revision must be positive"
        }
        require(sourceFingerprint == null || sourceFingerprint.isNotBlank()) {
            "Field shadow source fingerprint must not be blank"
        }
        require(message == null || message.isNotBlank()) { "Field shadow message must not be blank" }
    }

    companion object {
        fun failed(
            domainId: FieldDomainId? = null,
            message: String,
            source: Photon? = null,
        ): FieldShadowExecution = FieldShadowExecution(
            state = FieldShadowState.FAILED,
            domainId = domainId,
            message = message.ifBlank { "universal-field-shadow-failed" },
            sourcePhotonId = source?.id,
            sourceRevision = source?.revision,
            sourceFingerprint = source?.let(::runtimePhotonFingerprint),
        )

        fun blocked(
            domainId: FieldDomainId,
            message: String,
            source: Photon? = null,
        ): FieldShadowExecution = FieldShadowExecution(
            state = FieldShadowState.BLOCKED,
            domainId = domainId,
            message = message.ifBlank { "universal-field-shadow-blocked" },
            sourcePhotonId = source?.id,
            sourceRevision = source?.revision,
            sourceFingerprint = source?.let(::runtimePhotonFingerprint),
        )
    }
}

/** Complete deterministic identity of the immutable source Photon observed by shadow execution. */
fun runtimePhotonFingerprint(photon: Photon): String = StableFieldIds.fingerprint(
    "runtime-photon/v1",
    photon.id.value,
    photon.revision.toString(),
    photon.content,
    photon.mimeType,
    photon.phase.name,
    java.lang.Double.toHexString(photon.semanticMass),
    java.lang.Double.toHexString(photon.energy),
    java.lang.Double.toHexString(photon.confidence),
    photon.provenance.source,
    photon.provenance.actor,
    photon.provenance.createdAt.toString(),
    *photon.provenance.parentIds
        .map { "parent:${it.value}" }
        .sorted()
        .toTypedArray(),
    *photon.tags
        .map { "tag:$it" }
        .sorted()
        .toTypedArray(),
    *photon.relations
        .map { relation ->
            "relation:${relation.target.value}:${relation.type.name}:${java.lang.Double.toHexString(relation.weight)}"
        }
        .sorted()
        .toTypedArray(),
)

fun universalFieldShadowHealthNodeId(domainId: FieldDomainId): HealthNodeId = HealthNodeId(
    "field-shadow:${StableFieldIds.fingerprint("universal-field-shadow/v1", domainId.value)}",
)

fun interface FieldShadowProcessor {
    suspend fun process(photon: Photon): FieldShadowExecution
}

fun interface PhotonFieldRequestFactory {
    fun create(photon: Photon): FieldConvergenceRequest
}
