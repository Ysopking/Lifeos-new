package app.lifeos.core.data.security

import android.util.AtomicFile
import javax.crypto.SecretKey

/** Compatibility adapter preserving the versioned path-bound vault wire format. */
internal object VersionedPathBoundVaultSupport {
    fun loadOrCreateKey(alias: String): SecretKey =
        UnifiedVault.loadOrCreateKey(alias)

    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        containerVersion: Int,
        codecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray = UnifiedVault.encryptVersioned(
        plaintext = plaintext,
        key = key,
        containerVersion = containerVersion,
        codecVersion = codecVersion,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
    )

    fun decrypt(
        container: ByteArray,
        key: SecretKey,
        expectedContainerVersion: Int,
        expectedCodecVersion: Int,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray = UnifiedVault.decryptVersioned(
        container = container,
        key = key,
        expectedContainerVersion = expectedContainerVersion,
        expectedCodecVersion = expectedCodecVersion,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
    )

    fun atomicWrite(target: AtomicFile, bytes: ByteArray) =
        UnifiedVault.atomicWrite(target, bytes)

    fun readAtomic(target: AtomicFile, maxPlaintextBytes: Int): ByteArray =
        UnifiedVault.readAtomic(target, maxPlaintextBytes)
}
