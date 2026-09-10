package app.lifeos.core.data.learning

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bounded authenticated envelope for the durable continuous-learning cursor. */
object LearningWatermarkVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "Learning watermark payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Learning watermark payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT_VERSION)
                out.writeInt(cipher.iv.size)
                out.write(cipher.iv)
                out.writeInt(encrypted.size)
                out.write(encrypted)
            }
        }.toByteArray().also {
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted learning watermark state too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "Learning watermark container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "Learning watermark container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Unsupported learning watermark vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid learning watermark IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val encryptedLength = input.readInt()
        require(encryptedLength in 1..MAX_CONTAINER_BYTES) { "Invalid learning watermark ciphertext length" }
        require(encryptedLength == input.available()) { "Malformed learning watermark ciphertext length" }
        val encrypted = ByteArray(encryptedLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted).also {
            require(it.isNotEmpty()) { "Decrypted learning watermark payload is empty" }
            require(it.size <= MAX_PLAINTEXT_BYTES) { "Decrypted learning watermark payload too large" }
        }
    }
}