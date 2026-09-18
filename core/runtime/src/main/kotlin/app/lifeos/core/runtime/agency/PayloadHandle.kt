package app.lifeos.core.runtime.agency

import java.security.MessageDigest

/**
 * Content-addressed opaque payload identity. The handle contains only a SHA-256 digest; external
 * effect payload bytes remain in the encrypted payload repository and never enter Photon metadata.
 */
@JvmInline
value class PayloadHandle(val id: String) {
    init {
        require(id.startsWith(PREFIX))
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Payload handle must contain a lowercase SHA-256 digest"
        }
    }

    val fingerprint: String get() = id.removePrefix(PREFIX)

    companion object {
        const val PREFIX = "external-payload:"

        fun fromPayload(payload: ByteArray): PayloadHandle =
            PayloadHandle(PREFIX + sha256(payload))

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

interface ExternalPayloadRepository {
    suspend fun persist(handle: PayloadHandle, payload: ByteArray)
    suspend fun load(handle: PayloadHandle): ByteArray?
}
