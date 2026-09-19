package app.lifeos.core.data.security

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Shared binary container support for path-bound versioned LIFEOS vaults.
 *
 * Binary layout is intentionally identical to the existing WorldEquation vault format:
 * containerVersion, codecVersion, ivLength, iv, ciphertextLength, ciphertext.
 */
internal object VersionedPathBoundVaultSupport {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val CONTAINER_OVERHEAD_BYTES = 1024

    fun loadOrCreateKey(alias: String): SecretKey =
        EncryptedLedgerVaultSupport.loadOrCreateKey(alias)

    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        containerVersion: Int,
        codecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray {
        require(containerVersion > 0)
        require(codecVersion > 0)
        require(maxPlaintextBytes > 0)
        require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
            "Invalid versioned vault plaintext size"
        }
        require(associatedData.isNotEmpty()) {
            "Versioned path-bound vault requires associated data"
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(ciphertext.size + 64).let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(containerVersion)
                data.writeInt(codecVersion)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
            output.toByteArray()
        }.also { container ->
            require(container.size <= maxContainerBytes(maxPlaintextBytes)) {
                "Versioned vault container too large"
            }
        }
    }

    fun decrypt(
        container: ByteArray,
        key: SecretKey,
        expectedContainerVersion: Int,
        expectedCodecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray {
        require(expectedContainerVersion > 0)
        require(expectedCodecVersion > 0)
        require(maxPlaintextBytes > 0)
        require(associatedData.isNotEmpty()) {
            "Versioned path-bound vault requires associated data"
        }

        val maxContainerBytes = maxContainerBytes(maxPlaintextBytes)
        require(container.isNotEmpty() && container.size <= maxContainerBytes) {
            "Invalid versioned vault container size"
        }

        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == expectedContainerVersion) {
                "Unsupported versioned vault container"
            }
            require(data.readInt() == expectedCodecVersion) {
                "Unsupported versioned vault codec"
            }
            val ivLength = data.readInt()
            require(ivLength in 12..32) {
                "Invalid versioned vault IV length"
            }
            val iv = ByteArray(ivLength).also(data::readFully)
            val ciphertextLength = data.readInt()
            require(
                ciphertextLength in 1..maxContainerBytes &&
                    ciphertextLength == data.available()
            ) {
                "Malformed versioned vault ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(associatedData)
                doFinal(ciphertext)
            }
        }.also { plaintext ->
            require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
                "Invalid decrypted versioned vault payload size"
            }
        }
    }

    fun atomicWrite(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    fun readAtomic(
        target: AtomicFile,
        maxPlaintextBytes: Int,
    ): ByteArray {
        val maxContainerBytes = maxContainerBytes(maxPlaintextBytes)
        return target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxContainerBytes) {
                    "Versioned vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun maxContainerBytes(maxPlaintextBytes: Int): Int =
        Math.addExact(maxPlaintextBytes, CONTAINER_OVERHEAD_BYTES)
}
