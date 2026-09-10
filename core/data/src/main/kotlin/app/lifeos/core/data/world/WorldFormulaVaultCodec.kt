package app.lifeos.core.data.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated AES-GCM envelope for durable world-formula snapshots. */
object WorldFormulaVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "World formula payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "World formula payload too large" }
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
        return output.toByteArray().also { bytes ->
            require(bytes.size <= MAX_CONTAINER_BYTES) { "Encrypted world formula payload too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "World formula container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "World formula container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported world formula vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid world formula IV length" }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..MAX_CONTAINER_BYTES) { "Invalid world formula ciphertext length" }
        require(ciphertextLength == input.available()) { "Malformed world formula ciphertext length" }
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext).also { plaintext ->
            require(plaintext.isNotEmpty()) { "Decrypted world formula payload is empty" }
            require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Decrypted world formula payload too large" }
        }
    }
}
