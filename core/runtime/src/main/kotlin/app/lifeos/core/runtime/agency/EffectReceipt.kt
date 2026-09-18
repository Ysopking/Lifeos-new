package app.lifeos.core.runtime.agency

import java.time.Instant

enum class ExternalEffectState {
    CONFIRMED,
    REJECTED,
    FAILED,
    UNKNOWN_OUTCOME,
    USER_CHALLENGE_REQUIRED,
    WAITING_FOR_USER,
    RESUMED,
}

data class EffectReceipt(
    val actionId: String,
    val idempotencyKey: String,
    val state: ExternalEffectState,
    val recordedAt: Instant,
    val externalReference: String? = null,
    val observationFingerprint: String? = null,
    val challengeId: String? = null,
    val challengeResolutionFingerprint: String? = null,
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
        require(challengeId == null || challengeId.isNotBlank())
        require(
            challengeResolutionFingerprint == null ||
                challengeResolutionFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
        if (state == ExternalEffectState.WAITING_FOR_USER ||
            state == ExternalEffectState.RESUMED
        ) {
            require(!challengeId.isNullOrBlank()) {
                "Challenge lifecycle receipt requires a challenge id"
            }
        }
        if (state == ExternalEffectState.RESUMED) {
            require(challengeResolutionFingerprint != null) {
                "Resumed external effect requires challenge resolution evidence"
            }
        }
        require(detail == null || detail.isNotBlank())
    }
}

interface ExternalEffectReceiptRepository {
    suspend fun load(actionId: String): EffectReceipt?
    suspend fun save(receipt: EffectReceipt)
}
