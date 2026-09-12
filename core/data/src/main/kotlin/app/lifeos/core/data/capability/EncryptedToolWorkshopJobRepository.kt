package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.ToolWorkshopJobEvent
import app.lifeos.core.runtime.capability.ToolWorkshopJobEventLogCodec
import app.lifeos.core.runtime.capability.ToolWorkshopJobRepository
import app.lifeos.core.runtime.capability.ToolWorkshopJobRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted append-only V11 ToolWorkshop job ledger for the private APK. */
class EncryptedToolWorkshopJobRepository(context: Context) : ToolWorkshopJobRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): ToolWorkshopJobRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock ToolWorkshopJobRepositoryLoadReport(emptyList())
            try {
                ToolWorkshopJobRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                ToolWorkshopJobRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: ToolWorkshopJobEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val events = if (exists(file)) readStrict() else emptyList()
            val currentRevision = events.lastOrNull()?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "ToolWorkshop job append revision mismatch"
            }
            write(events + event)
            true
        }
    }

    private fun readStrict(): List<ToolWorkshopJobEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return ToolWorkshopJobEventLogCodec.decode(plaintext)
    }

    private fun write(events: List<ToolWorkshopJobEvent>) {
        val plaintext = ToolWorkshopJobEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "ToolWorkshop job vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "tool-workshop-job-ledger"
        const val FILE_NAME = "tool-workshop.twj"
        const val KEY_ALIAS = "lifeos.tool.workshop.job.v1"
        val processMutex = Mutex()
    }
}
