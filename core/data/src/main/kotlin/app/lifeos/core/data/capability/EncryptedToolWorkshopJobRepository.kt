package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.ToolWorkshopJobEvent
import app.lifeos.core.runtime.capability.ToolWorkshopJobEventLogCodec
import app.lifeos.core.runtime.capability.ToolWorkshopJobRepository
import app.lifeos.core.runtime.capability.ToolWorkshopJobRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-job segmented encrypted ToolWorkshop ledger with a tiny global revision head. */
class EncryptedToolWorkshopJobRepository(context: Context) : ToolWorkshopJobRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val jobsDirectory = directory.resolve("jobs")
    private val headFile = directory.resolve("head.twj")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): ToolWorkshopJobRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val unreadable = mutableListOf<String>()
            val events = eventFiles().mapNotNull { file ->
                runCatching { readEvent(file) }
                    .onFailure { unreadable += file.relativeTo(directory).path }
                    .getOrNull()
            }
            ToolWorkshopJobRepositoryLoadReport(events.sortedBy { it.revision }, unreadable.sorted())
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: ToolWorkshopJobEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            val currentRevision = readHeadOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "ToolWorkshop job append revision mismatch"
            }
            val target = eventFile(event)
            if (exists(target)) {
                require(readEvent(target) == event) { "ToolWorkshop job event revision collision" }
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
        val legacy = ToolWorkshopJobEventLogCodec.decode(
            decrypt(legacyFile, ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        legacy.sortedBy { it.revision }.forEachIndexed { index, event ->
            require(event.revision == index.toLong() + 1L)
            writeEvent(eventFile(event), event)
        }
        writeHead(legacy.lastOrNull()?.revision ?: 0L)
    }

    private fun readEvent(file: File): ToolWorkshopJobEvent {
        val events = ToolWorkshopJobEventLogCodec.decode(
            decrypt(file, ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        require(events.size == 1)
        return events.single()
    }

    private fun writeEvent(file: File, event: ToolWorkshopJobEvent) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        writeEncrypted(
            file,
            ToolWorkshopJobEventLogCodec.encode(listOf(event)),
            ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun readHeadOrRecover(): Long {
        if (exists(headFile)) runCatching { return readHead() }
        val recovered = eventFiles().mapNotNull { file ->
            file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
        }.maxOrNull() ?: 0L
        if (recovered > 0L) {
            val revisions = eventFiles().mapNotNull { file ->
                file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
            }.toSet()
            require((1L..recovered).all { it in revisions }) {
                "ToolWorkshop event segments are not contiguous"
            }
        }
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

    private fun eventFile(event: ToolWorkshopJobEvent): File {
        val jobKey = sha256(event.definition.id.value)
        return jobsDirectory.resolve(jobKey)
            .resolve("$EVENT_PREFIX${event.revision.toString().padStart(20, '0')}$EVENT_SUFFIX")
    }

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return jobsDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(EVENT_PREFIX) && it.name.endsWith(EVENT_SUFFIX) }
            .sortedBy { it.path }
            .toList()
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "ToolWorkshop job vault unavailable" }
        check(jobsDirectory.isDirectory || jobsDirectory.mkdirs()) { "ToolWorkshop job directory unavailable" }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "tool-workshop-job-ledger"
        const val FILE_NAME = "tool-workshop.twj"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".twj"
        const val KEY_ALIAS = "lifeos.tool.workshop.job.v1"
        val processMutex = Mutex()
    }
}
