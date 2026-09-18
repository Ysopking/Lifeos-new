package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.HotSwapEvent
import app.lifeos.core.runtime.capability.HotSwapEventLogCodec
import app.lifeos.core.runtime.capability.HotSwapRepository
import app.lifeos.core.runtime.capability.HotSwapRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Segmented encrypted V10 hot-swap event log. */
class EncryptedHotSwapRepository(context: Context) : HotSwapRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val eventsDirectory = directory.resolve("events")
    private val headFile = directory.resolve("head.hswap")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): HotSwapRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val unreadable = mutableListOf<String>()
            val events = eventFiles().mapNotNull { file ->
                runCatching { readEvent(file) }
                    .onFailure { unreadable += file.name }
                    .getOrNull()
            }
            HotSwapRepositoryLoadReport(events.sortedBy { it.revision }, unreadable.sorted())
        }
    }

    override suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                require(expectedRevision >= 0L)
                ensureMigrated()
                val currentRevision = readHeadOrRecover()
                if (currentRevision != expectedRevision) return@withLock false
                require(event.revision == expectedRevision + 1L) {
                    "Hot-swap append revision mismatch"
                }
                val target = eventFile(event.revision)
                if (exists(target)) {
                    require(readEvent(target) == event) { "Hot-swap revision collision" }
                } else {
                    writeEvent(target, event)
                }
                writeHead(event.revision)
                true
            }
        }

    private fun ensureMigrated() {
        ensureDirectory()
        if (exists(headFile) || eventFiles().isNotEmpty()) return
        if (!exists(legacyFile)) {
            writeHead(0L)
            return
        }
        readLegacy().sortedBy { it.revision }.forEachIndexed { index, event ->
            require(event.revision == index.toLong() + 1L)
            writeEvent(eventFile(event.revision), event)
        }
        writeHead(readLegacy().lastOrNull()?.revision ?: 0L)
    }

    private fun readLegacy(): List<HotSwapEvent> =
        HotSwapEventLogCodec.decode(decrypt(legacyFile, HotSwapEventLogCodec.MAX_PAYLOAD_BYTES))

    private fun readEvent(file: File): HotSwapEvent {
        val events = HotSwapEventLogCodec.decode(
            decrypt(file, HotSwapEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        require(events.size == 1)
        return events.single().also { event ->
            require(file == eventFile(event.revision))
        }
    }

    private fun writeEvent(file: File, event: HotSwapEvent) {
        writeEncrypted(
            file,
            HotSwapEventLogCodec.encode(listOf(event)),
            HotSwapEventLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun readHeadOrRecover(): Long {
        if (exists(headFile)) runCatching { return readHead() }
        val recovered = eventFiles().mapNotNull { file ->
            file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
        }.maxOrNull() ?: 0L
        if (recovered > 0) require((1L..recovered).all { exists(eventFile(it)) })
        writeHead(recovered)
        return recovered
    }

    private fun readHead(): Long {
        val bytes = decrypt(headFile, 64)
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).long.also { require(it >= 0L) }
    }

    private fun writeHead(revision: Long) {
        writeEncrypted(
            headFile,
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revision).array(),
            64,
        )
    }

    private fun decrypt(file: File, maxPlaintextBytes: Int): ByteArray =
        EncryptedLedgerVaultSupport.decrypt(
            EncryptedLedgerVaultSupport.readAtomic(file, maxPlaintextBytes),
            key,
            maxPlaintextBytes,
        )

    private fun writeEncrypted(file: File, plaintext: ByteArray, maxPlaintextBytes: Int) {
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(plaintext, key, maxPlaintextBytes),
        )
    }

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return eventsDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(EVENT_PREFIX) && it.name.endsWith(EVENT_SUFFIX) }
            .sortedBy { it.name }
    }

    private fun eventFile(revision: Long): File =
        eventsDirectory.resolve("$EVENT_PREFIX${revision.toString().padStart(20, '0')}$EVENT_SUFFIX")

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Hot-swap vault unavailable" }
        check(eventsDirectory.isDirectory || eventsDirectory.mkdirs()) { "Hot-swap event directory unavailable" }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "hot-swap-ledger"
        const val FILE_NAME = "hot-swap.hswap"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".hswap"
        const val KEY_ALIAS = "lifeos.hot.swap.v1"
        val processMutex = Mutex()
    }
}
