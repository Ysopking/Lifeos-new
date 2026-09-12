package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.HotSwapEvent
import app.lifeos.core.runtime.capability.HotSwapEventLogCodec
import app.lifeos.core.runtime.capability.HotSwapRepository
import app.lifeos.core.runtime.capability.HotSwapRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted append-only V10 hot-swap ledger for the private APK. */
class EncryptedHotSwapRepository(context: Context) : HotSwapRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): HotSwapRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock HotSwapRepositoryLoadReport(emptyList())
            try {
                HotSwapRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                HotSwapRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val events = if (exists(file)) readStrict() else emptyList()
            val currentRevision = events.lastOrNull()?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) { "Hot-swap append revision mismatch" }
            write(events + event)
            true
        }
    }

    private fun readStrict(): List<HotSwapEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = HotSwapEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = HotSwapEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return HotSwapEventLogCodec.decode(plaintext)
    }

    private fun write(events: List<HotSwapEvent>) {
        val plaintext = HotSwapEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = HotSwapEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Hot-swap vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "hot-swap-ledger"
        const val FILE_NAME = "hot-swap.hswap"
        const val KEY_ALIAS = "lifeos.hot.swap.v1"
        val processMutex = Mutex()
    }
}
