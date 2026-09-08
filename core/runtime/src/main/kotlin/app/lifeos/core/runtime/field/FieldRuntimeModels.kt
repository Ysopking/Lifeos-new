package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
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
        require(message == null || message.isNotBlank()) { "Field shadow message must not be blank" }
    }

    companion object {
        fun failed(domainId: FieldDomainId? = null, message: String): FieldShadowExecution =
            FieldShadowExecution(
                state = FieldShadowState.FAILED,
                domainId = domainId,
                message = message.ifBlank { "universal-field-shadow-failed" },
            )

        fun blocked(domainId: FieldDomainId, message: String): FieldShadowExecution =
            FieldShadowExecution(
                state = FieldShadowState.BLOCKED,
                domainId = domainId,
                message = message.ifBlank { "universal-field-shadow-blocked" },
            )
    }
}

fun universalFieldShadowHealthNodeId(domainId: FieldDomainId): HealthNodeId = HealthNodeId(
    "field-shadow:${StableFieldIds.fingerprint("universal-field-shadow/v1", domainId.value)}",
)

fun interface FieldShadowProcessor {
    suspend fun process(photon: Photon): FieldShadowExecution
}

fun interface PhotonFieldRequestFactory {
    fun create(photon: Photon): FieldConvergenceRequest
}
