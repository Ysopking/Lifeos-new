package app.lifeos.core.runtime.agency

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetReservationId

data class ExternalResourceReservation(
    val accountId: ResourceBudgetAccountId,
    val reservationId: ResourceBudgetReservationId,
)

data class ExternalActionContract(
    val actionId: String,
    val idempotencyKey: String,
    val endpoint: ExternalEndpoint,
    val operation: String,
    val payloadFingerprint: String,
    val requiredOwnerPolicy: OwnerEffectRequest,
    val resourceReservation: ExternalResourceReservation?,
    val sourcePhotonRefs: Set<PhotonRevisionRef>,
    val expectedObservation: ExternalObservation,
    val credentialHandle: CredentialHandle? = null,
) {
    init {
        require(actionId.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(operation.isNotBlank())
        require(payloadFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "External payload fingerprint must be SHA-256"
        }
        require(sourcePhotonRefs.isNotEmpty()) { "External action requires source Photon revisions" }
        require(requiredOwnerPolicy.resource == endpoint.uri) {
            "External action OwnerPolicy resource must be the exact endpoint"
        }
        resourceReservation?.let { reservation ->
            require(requiredOwnerPolicy.budgetAccountId == reservation.accountId)
            require(requiredOwnerPolicy.budgetReservationId == reservation.reservationId)
        }
    }
}
