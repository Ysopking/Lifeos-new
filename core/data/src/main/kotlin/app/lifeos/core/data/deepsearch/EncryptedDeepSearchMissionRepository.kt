package app.lifeos.core.data.deepsearch

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEvent
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventLogCodec
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepository
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted append-only V12 mission ledger for the private APK. */
class EncryptedDeepSearchMissionRepository(context: Context) : DeepSearchMissionRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): DeepSearchMissionRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock DeepSearchMissionRepositoryLoadReport(emptyList())
            try {
                DeepSearchMissionRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                DeepSearchMissionRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: DeepSearchMissionEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val events = if (exists(file)) readStrict() else emptyList()
            val currentRevision = events.lastOrNull()?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) { "DeepSearch mission append revision mismatch" }
            write(events + event)
            true
        }
    }

    private fun readStrict(): List<DeepSearchMissionEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return DeepSearchMissionEventLogCodec.decode(plaintext)
    }

    private fun write(events: List<DeepSearchMissionEvent>) {
        val plaintext = DeepSearchMissionEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "DeepSearch mission vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "deep-search-v2"
        const val FILE_NAME = "missions.dsmission"
        const val KEY_ALIAS = "lifeos.deep.search.mission.v2"
        val processMutex = Mutex()
    }
}
