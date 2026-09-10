package app.lifeos.core.runtime.thought

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Exact length-prefixed SHA-256; unlike semantic field ids this intentionally preserves case. */
internal object ThoughtFingerprint {
    fun exact(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { raw ->
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            val length = bytes.size
            digest.update(
                byteArrayOf(
                    ((length ushr 24) and 0xff).toByte(),
                    ((length ushr 16) and 0xff).toByte(),
                    ((length ushr 8) and 0xff).toByte(),
                    (length and 0xff).toByte(),
                )
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
