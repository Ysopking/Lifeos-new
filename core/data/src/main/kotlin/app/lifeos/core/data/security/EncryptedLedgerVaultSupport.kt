package app.lifeos.core.data.security

import java.io.File
import javax.crypto.SecretKey

/** Compatibility adapter preserving the legacy V1 encrypted-ledger wire format. */
internal object EncryptedLedgerVaultSupport {
    fun loadOrCreateKey(alias: String): SecretKey =
        UnifiedVault.loadOrCreateKey(alias)

    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray = UnifiedVault.encryptLegacyV1(
        plaintext = plaintext,
        key = key,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
    )

    fun decrypt(
        container: ByteArray,
        key: SecretKey,
        maxPlaintextBytes: Int,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray = UnifiedVault.decryptLegacyV1(
        container = container,
        key = key,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
    )

    fun atomicWrite(target: File, bytes: ByteArray) =
        UnifiedVault.atomicWrite(target, bytes)

    fun readAtomic(target: File, maxPlaintextBytes: Int): ByteArray =
        UnifiedVault.readAtomic(target, maxPlaintextBytes)
}
