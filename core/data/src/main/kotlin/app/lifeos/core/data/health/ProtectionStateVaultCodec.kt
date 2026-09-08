package app.lifeos.core.data.health

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bounded AES-GCM envelope for durable runtime protection state. */
object ProtectionStateVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = 1024 * 1024
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "Protection payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Protection payload too large" }
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
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted protection state too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "Protection container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "Protection container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Unsupported protection vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid protection IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val encryptedLength = input.readInt()
        require(encryptedLength in 1..MAX_CONTAINER_BYTES) { "Invalid protection ciphertext length" }
        require(encryptedLength == input.available()) { "Malformed protection ciphertext length" }
        val encrypted = ByteArray(encryptedLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted).also {
            require(it.isNotEmpty()) { "Decrypted protection payload is empty" }
            require(it.size <= MAX_PLAINTEXT_BYTES) { "Decrypted protection payload too large" }
        }
    }
}
