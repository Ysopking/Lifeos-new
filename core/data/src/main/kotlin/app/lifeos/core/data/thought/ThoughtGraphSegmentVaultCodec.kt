package app.lifeos.core.data.thought

import app.lifeos.core.runtime.thought.ThoughtGraphDeltaSegmentCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated AES-GCM envelope for one immutable content-addressed thought-graph segment. */
internal object ThoughtGraphSegmentVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = ThoughtGraphDeltaSegmentCodec.MAX_PAYLOAD_BYTES
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "Thought graph segment payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Thought graph segment payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plaintext)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(FORMAT_VERSION)
            stream.writeInt(cipher.iv.size)
            stream.write(cipher.iv)
            stream.writeInt(ciphertext.size)
            stream.write(ciphertext)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted thought graph segment too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "Thought graph segment container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "Thought graph segment container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported thought graph segment vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid thought graph segment IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..MAX_CONTAINER_BYTES) { "Invalid thought graph segment ciphertext length" }
        require(ciphertextLength == input.available()) { "Malformed thought graph segment ciphertext length" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext).also {
            require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES) {
                "Invalid decrypted thought graph segment size"
            }
        }
    }
}
