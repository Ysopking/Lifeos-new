package app.lifeos.core.data.thought

import app.lifeos.core.runtime.ThoughtMatrixDurableStateCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated AES-GCM envelope for the exact process-level ThoughtMatrix state. */
internal object ThoughtMatrixStateVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = ThoughtMatrixDurableStateCodec.MAX_PAYLOAD_BYTES
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "ThoughtMatrix state payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "ThoughtMatrix state payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
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
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted ThoughtMatrix state too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "ThoughtMatrix state container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "ThoughtMatrix state container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported ThoughtMatrix vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid ThoughtMatrix IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..MAX_CONTAINER_BYTES) { "Invalid ThoughtMatrix ciphertext length" }
        require(ciphertextLength == input.available()) { "Malformed ThoughtMatrix ciphertext length" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext).also {
            require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES) {
                "Invalid decrypted ThoughtMatrix state size"
            }
        }
    }
}
