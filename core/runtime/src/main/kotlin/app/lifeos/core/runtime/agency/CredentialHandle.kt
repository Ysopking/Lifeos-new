package app.lifeos.core.runtime.agency

/**
 * Opaque reference into a platform credential provider. Secret material is never represented here
 * and therefore cannot be serialized into a Photon or decision trace.
 */
@JvmInline
value class CredentialHandle(val id: String) {
    init {
        require(id.isNotBlank())
        require(!id.contains("://")) { "Credential handle must be opaque, not a secret-bearing URI" }
        require(id.length <= 256)
    }
}
