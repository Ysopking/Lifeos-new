package app.lifeos.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared bounded AES-GCM/Android-Keystore helpers for durable local LIFEOS ledgers. */
internal object EncryptedLedgerVaultSupport {
    private const val VERSION = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val CONTAINER_OVERHEAD_BYTES = 1024

    fun loadOrCreateKey(alias: String): SecretKey {
        require(alias.isNotBlank()) { "Keystore alias must not be blank" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
    ): ByteArray {
        require(maxPlaintextBytes > 0)
        require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
            "Invalid encrypted-ledger plaintext size"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(VERSION)
                stream.writeInt(cipher.iv.size)
                stream.write(cipher.iv)
                stream.writeInt(ciphertext.size)
                stream.write(ciphertext)
            }
            output.toByteArray()
        }.also { container ->
            require(container.size <= maxContainerBytes(maxPlaintextBytes)) {
                "Encrypted ledger container too large"
            }
        }
    }

    fun decrypt(
        container: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
    ): ByteArray {
        val maxContainerBytes = maxContainerBytes(maxPlaintextBytes)
        require(container.isNotEmpty() && container.size <= maxContainerBytes) {
            "Invalid encrypted-ledger container size"
        }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == VERSION) { "Unsupported encrypted-ledger vault version" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid encrypted-ledger IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..maxContainerBytes && ciphertextLength == input.available()) {
            "Malformed encrypted-ledger ciphertext length"
        }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(ciphertext)
        }.also { plaintext ->
            require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
                "Invalid decrypted-ledger payload size"
            }
        }
    }

    fun atomicWrite(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun readAtomic(target: File, maxPlaintextBytes: Int): ByteArray {
        val maxContainerBytes = maxContainerBytes(maxPlaintextBytes)
        return AtomicFile(target).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxContainerBytes) {
                    "Encrypted ledger file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun maxContainerBytes(maxPlaintextBytes: Int): Int =
        Math.addExact(maxPlaintextBytes, CONTAINER_OVERHEAD_BYTES)
}
