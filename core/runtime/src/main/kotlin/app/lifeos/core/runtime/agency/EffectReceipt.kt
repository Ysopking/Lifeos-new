package app.lifeos.core.runtime.agency

import java.time.Instant

enum class ExternalEffectState {
    CONFIRMED,
    REJECTED,
    FAILED,
    UNKNOWN_OUTCOME,
    USER_CHALLENGE_REQUIRED,
}

data class EffectReceipt(
    val actionId: String,
    val idempotencyKey: String,
    val state: ExternalEffectState,
    val recordedAt: Instant,
    val externalReference: String? = null,
    val observationFingerprint: String? = null,
    val detail: String? = null,
) {
    init {
        require(actionId.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(externalReference == null || externalReference.isNotBlank())
        require(
            observationFingerprint == null ||
                observationFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
        require(detail == null || detail.isNotBlank())
    }
}

interface ExternalEffectReceiptRepository {
    suspend fun load(actionId: String): EffectReceipt?
    suspend fun save(receipt: EffectReceipt)
}
