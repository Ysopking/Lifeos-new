package app.lifeos.core.data.security

import android.util.AtomicFile
import java.io.File
import javax.crypto.SecretKey

internal object UnifiedVault {
    fun loadOrCreateKey(alias: String): SecretKey =
        UnifiedVaultKeyProvider.loadOrCreate(alias)

    fun encryptLegacyV1(
        plaintext: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray = UnifiedVaultEnvelope.encryptLegacyV1(
        plaintext = plaintext,
        key = key,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
        maxContainerBytes = UnifiedVaultIo.maxContainerBytes(maxPlaintextBytes),
    )

    fun decryptLegacyV1(
        container: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray = UnifiedVaultEnvelope.decryptLegacyV1(
        container = container,
        key = key,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
        maxContainerBytes = UnifiedVaultIo.maxContainerBytes(maxPlaintextBytes),
    )

    fun encryptVersioned(
        plaintext: ByteArray,
        key: SecretKey,
        containerVersion: Int,
        codecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray = UnifiedVaultEnvelope.encryptVersioned(
        plaintext = plaintext,
        key = key,
        containerVersion = containerVersion,
        codecVersion = codecVersion,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
        maxContainerBytes = UnifiedVaultIo.maxContainerBytes(maxPlaintextBytes),
    )

    fun decryptVersioned(
        container: ByteArray,
        key: SecretKey,
        expectedContainerVersion: Int,
        expectedCodecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray = UnifiedVaultEnvelope.decryptVersioned(
        container = container,
        key = key,
        expectedContainerVersion = expectedContainerVersion,
        expectedCodecVersion = expectedCodecVersion,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
        maxContainerBytes = UnifiedVaultIo.maxContainerBytes(maxPlaintextBytes),
    )

    fun atomicWrite(target: File, bytes: ByteArray) = UnifiedVaultIo.atomicWrite(target, bytes)
    fun atomicWrite(target: AtomicFile, bytes: ByteArray) = UnifiedVaultIo.atomicWrite(target, bytes)
    fun readAtomic(target: File, maxPlaintextBytes: Int): ByteArray =
        UnifiedVaultIo.readAtomic(target, maxPlaintextBytes)
    fun readAtomic(target: AtomicFile, maxPlaintextBytes: Int): ByteArray =
        UnifiedVaultIo.readAtomic(target, maxPlaintextBytes)
}
