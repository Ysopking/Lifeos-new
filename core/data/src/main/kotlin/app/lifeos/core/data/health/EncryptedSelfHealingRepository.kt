package app.lifeos.core.data.health

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventLogCodec
import app.lifeos.core.runtime.health.SelfHealingRepository
import app.lifeos.core.runtime.health.SelfHealingRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted append-only V9 self-healing ledger for the private APK. */
class EncryptedSelfHealingRepository(context: Context) : SelfHealingRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): SelfHealingRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock SelfHealingRepositoryLoadReport(emptyList())
            try {
                SelfHealingRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                SelfHealingRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: SelfHealingEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val events = if (exists(file)) readStrict() else emptyList()
            val currentRevision = events.lastOrNull()?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "Self-healing append revision mismatch"
            }
            write(events + event)
            true
        }
    }

    private fun readStrict(): List<SelfHealingEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return SelfHealingEventLogCodec.decode(plaintext)
    }

    private fun write(events: List<SelfHealingEvent>) {
        val plaintext = SelfHealingEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Self-healing vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "self-healing-ledger"
        const val FILE_NAME = "self-healing.sheal"
        const val KEY_ALIAS = "lifeos.self.healing.v1"
        val processMutex = Mutex()
    }
}
