package app.lifeos.core.data.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object UnifiedVaultEnvelope {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encryptLegacyV1(
        plaintext: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
        maxContainerBytes: Int,
    ): ByteArray {
        require(maxPlaintextBytes > 0)
        require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
            "Invalid encrypted-ledger plaintext size"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            if (associatedData.isNotEmpty()) updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
            output.toByteArray()
        }.also { container ->
            require(container.size <= maxContainerBytes) { "Encrypted ledger container too large" }
        }
    }

    fun decryptLegacyV1(
        container: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
        maxContainerBytes: Int,
    ): ByteArray {
        require(container.isNotEmpty() && container.size <= maxContainerBytes) {
            "Invalid encrypted-ledger container size"
        }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == 1) { "Unsupported encrypted-ledger vault version" }
            val ivLength = data.readInt()
            require(ivLength in 12..32) { "Invalid encrypted-ledger IV length" }
            val iv = ByteArray(ivLength).also(data::readFully)
            val ciphertextLength = data.readInt()
            require(ciphertextLength in 1..maxContainerBytes && ciphertextLength == data.available()) {
                "Malformed encrypted-ledger ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                if (associatedData.isNotEmpty()) updateAAD(associatedData)
                doFinal(ciphertext)
            }
        }.also { plaintext ->
            require(plaintext.isNotEmpty() && plaintext.size <= maxPlaintextBytes) {
                "Invalid decrypted-ledger payload size"
            }
        }
    }

    fun encryptVersioned(
        plaintext: ByteArray,
        key: SecretKey,
        containerVersion: Int,
        codecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
        maxContainerBytes: Int,
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
            require(container.size <= maxContainerBytes) { "Versioned vault container too large" }
        }
    }

    fun decryptVersioned(
        container: ByteArray,
        key: SecretKey,
        expectedContainerVersion: Int,
        expectedCodecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
        maxContainerBytes: Int,
    ): ByteArray {
        require(expectedContainerVersion > 0)
        require(expectedCodecVersion > 0)
        require(maxPlaintextBytes > 0)
        require(associatedData.isNotEmpty()) {
            "Versioned path-bound vault requires associated data"
        }
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
            require(ivLength in 12..32) { "Invalid versioned vault IV length" }
            val iv = ByteArray(ivLength).also(data::readFully)
            val ciphertextLength = data.readInt()
            require(ciphertextLength in 1..maxContainerBytes && ciphertextLength == data.available()) {
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
}
