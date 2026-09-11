package app.lifeos.core.data.policy

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyEventLogCodec
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted append-only V14 owner-policy ledger for the private APK. The complete event log is
 * rewritten atomically under one process-wide CAS lock so reconstruction cannot lose revocations.
 */
class EncryptedOwnerPolicyRepository(context: Context) : OwnerPolicyRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock OwnerPolicyRepositoryLoadReport(emptyList())
            try {
                OwnerPolicyRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                OwnerPolicyRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: OwnerPolicyEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val events = if (exists(file)) readStrict() else emptyList()
            val currentRevision = events.lastOrNull()?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "Owner policy append revision mismatch"
            }
            write(events + event)
            true
        }
    }

    private fun readStrict(): List<OwnerPolicyEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return OwnerPolicyEventLogCodec.decode(plaintext)
    }

    private fun write(events: List<OwnerPolicyEvent>) {
        val plaintext = OwnerPolicyEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Owner policy vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "owner-policy-ledger"
        const val FILE_NAME = "owner-policy.opolicy"
        const val KEY_ALIAS = "lifeos.owner.policy.v1"
        val processMutex = Mutex()
    }
}
