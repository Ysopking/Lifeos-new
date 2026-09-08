package app.lifeos.core.data.field

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Binary AES-GCM envelope used by the durable field snapshot vault. */
object FieldSnapshotVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "Field snapshot payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Field snapshot payload too large" }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val encrypted = cipher.doFinal(plaintext)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(FORMAT_VERSION)
            stream.writeInt(cipher.iv.size)
            stream.write(cipher.iv)
            stream.writeInt(encrypted.size)
            stream.write(encrypted)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted field snapshot too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty()) { "Field snapshot container must not be empty" }
        require(container.size <= MAX_CONTAINER_BYTES) { "Field snapshot container too large" }

        val input = DataInputStream(ByteArrayInputStream(container))
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Unsupported field snapshot vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid field snapshot IV length" }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val encryptedLength = input.readInt()
        require(encryptedLength in 1..MAX_CONTAINER_BYTES) { "Invalid field snapshot ciphertext length" }
        require(encryptedLength == input.available()) { "Malformed field snapshot ciphertext length" }
        val encrypted = ByteArray(encryptedLength)
        input.readFully(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted).also {
            require(it.isNotEmpty()) { "Decrypted field snapshot payload is empty" }
            require(it.size <= MAX_PLAINTEXT_BYTES) { "Decrypted field snapshot payload too large" }
        }
    }
}
