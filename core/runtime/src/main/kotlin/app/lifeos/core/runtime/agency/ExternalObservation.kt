package app.lifeos.core.runtime.agency

data class ExternalObservation(
    val kind: String,
    val expectedFingerprint: String,
) {
    init {
        require(kind.isNotBlank())
        require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Expected external observation fingerprint must be SHA-256"
        }
    }
}
