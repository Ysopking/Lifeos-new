package app.lifeos.core.data.agency

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.agency.ExternalPayloadRepository
import app.lifeos.core.runtime.agency.PayloadHandle
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable encrypted external-effect payloads addressed by their SHA-256 PayloadHandle. */
class EncryptedExternalPayloadRepository(context: Context) : ExternalPayloadRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun persist(
        handle: PayloadHandle,
        payload: ByteArray,
    ) = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(payload.isNotEmpty()) { "External effect payload must not be empty" }
            require(PayloadHandle.fromPayload(payload) == handle) {
                "External payload bytes do not match their content-addressed handle"
            }
            ensureDirectory()
            val file = target(handle)
            if (exists(file)) {
                val existing = read(file)
                require(existing.contentEquals(payload)) {
                    "External payload handle collision"
                }
                return@withLock
            }
            write(file, payload)
        }
    }

    override suspend fun load(handle: PayloadHandle): ByteArray? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val file = target(handle)
                if (!exists(file)) return@withLock null
                read(file).also { payload ->
                    require(PayloadHandle.fromPayload(payload) == handle) {
                        "Encrypted external payload content hash mismatch"
                    }
                }
            }
        }

    private fun read(file: File): ByteArray {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
        )
        return EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
        )
    }

    private fun write(file: File, payload: ByteArray) {
        require(payload.size <= MAX_PLAINTEXT_BYTES) {
            "External effect payload exceeds encrypted payload limit"
        }
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(
                plaintext = payload,
                key = key,
                maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            ),
        )
    }

    private fun target(handle: PayloadHandle): File =
        directory.resolve(handle.fingerprint + PAYLOAD_SUFFIX)

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "External effect payload vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "external-effect-payloads"
        const val PAYLOAD_SUFFIX = ".payload"
        const val KEY_ALIAS = "lifeos.external.effect.payload.v1"
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        val processMutex = Mutex()
    }
}
