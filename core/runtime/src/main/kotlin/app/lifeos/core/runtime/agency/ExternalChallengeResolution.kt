package app.lifeos.core.runtime.agency

import app.lifeos.core.model.StableCognitiveIds

data class ExternalChallengeResolution(
    val challengeId: String,
    val confirmedByUser: Boolean,
    val credentialHandle: CredentialHandle? = null,
    val evidenceFingerprint: String,
) {
    init {
        require(challengeId.isNotBlank())
        require(evidenceFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Challenge resolution evidence must be a SHA-256 fingerprint"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "external-challenge-resolution/v1",
        challengeId,
        confirmedByUser.toString(),
        credentialHandle?.id.orEmpty(),
        evidenceFingerprint,
    )
}
