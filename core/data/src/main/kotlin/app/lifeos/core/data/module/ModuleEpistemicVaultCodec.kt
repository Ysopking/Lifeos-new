package app.lifeos.core.data.module

import app.lifeos.core.runtime.module.ModuleEpistemicStateCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM envelope for persisted module epistemic state. */
object ModuleEpistemicVaultCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PLAINTEXT_BYTES = ModuleEpistemicStateCodec.MAX_PAYLOAD_BYTES
    const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 512
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty()) { "Module epistemic payload must not be empty" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Module epistemic payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
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
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted module epistemic payload too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
            "Module epistemic vault container size invalid"
        }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported module epistemic vault format" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid module epistemic IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val encryptedLength = input.readInt()
        require(encryptedLength in 1..MAX_CONTAINER_BYTES && encryptedLength == input.available()) {
            "Malformed module epistemic ciphertext length"
        }
        val encrypted = ByteArray(encryptedLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted).also {
            require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES) {
                "Decrypted module epistemic payload size invalid"
            }
        }
    }
}
