package app.lifeos.core.data.health

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventLogCodec
import app.lifeos.core.runtime.health.SelfHealingRepository
import app.lifeos.core.runtime.health.SelfHealingRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-incident encrypted self-healing segments with a global revision head. */
class EncryptedSelfHealingRepository(context: Context) : SelfHealingRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val incidentsDirectory = directory.resolve("incidents")
    private val headFile = directory.resolve("head.sheal")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): SelfHealingRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val unreadable = mutableListOf<String>()
            val events = eventFiles().mapNotNull { file ->
                runCatching { readValidatedEvent(file) }
                    .onFailure { unreadable += file.relativeTo(directory).path }
                    .getOrNull()
            }
            if (unreadable.isEmpty()) {
                readHeadOrRecover()
            }
            SelfHealingRepositoryLoadReport(events.sortedBy { it.revision }, unreadable.sorted())
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: SelfHealingEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            requireReadableEventHistory()
            val currentRevision = readHeadOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "Self-healing append revision mismatch"
            }
            val target = eventFile(event)
            if (exists(target)) {
                require(readValidatedEvent(target) == event) { "Self-healing event revision collision" }
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
        val legacy = SelfHealingEventLogCodec.decode(
            decrypt(legacyFile, SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        legacy.sortedBy { it.revision }.forEachIndexed { index, event ->
            require(event.revision == index.toLong() + 1L) {
                "Legacy self-healing revisions are not contiguous"
            }
            writeEvent(eventFile(event), event)
        }
        writeHead(legacy.lastOrNull()?.revision ?: 0L)
    }

    private fun readEvent(file: File): SelfHealingEvent {
        val event = SelfHealingEventLogCodec.decodeSegment(
            decrypt(file, SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        return event
    }

    private fun readValidatedEvent(file: File): SelfHealingEvent {
        val event = readEvent(file)
        require(event.revision == segmentRevision(file)) {
            "Self-healing event payload revision does not match segment path"
        }
        val incidentDirectory = requireNotNull(file.parentFile) {
            "Self-healing event segment has no incident directory"
        }
        require(incidentDirectory.parentFile == incidentsDirectory) {
            "Self-healing event segment is not stored under the incident directory"
        }
        require(incidentDirectory.name == sha256(event.incidentId.value)) {
            "Self-healing event incident does not match segment path"
        }
        return event
    }

    private fun requireReadableEventHistory() {
        eventFiles().forEach { file -> readValidatedEvent(file) }
    }

    private fun writeEvent(file: File, event: SelfHealingEvent) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        writeEncrypted(
            file,
            SelfHealingEventLogCodec.encodeSegment(event),
            SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun eventFile(event: SelfHealingEvent): File =
        incidentsDirectory.resolve(sha256(event.incidentId.value))
            .resolve("$EVENT_PREFIX${event.revision.toString().padStart(20, '0')}$EVENT_SUFFIX")

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return incidentsDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(EVENT_PREFIX) && it.name.endsWith(EVENT_SUFFIX) }
            .sortedBy { it.path }
            .toList()
    }

    private fun readHeadOrRecover(): Long {
        val files = eventFiles()
        val byRevision = files.groupBy(::segmentRevision)
        require(byRevision.values.all { it.size == 1 }) {
            "Self-healing contains duplicate global event revisions"
        }
        val revisions = byRevision.keys.sorted()
        val recovered = revisions.lastOrNull() ?: 0L
        require(revisions == if (recovered == 0L) emptyList() else (1L..recovered).toList()) {
            "Self-healing event segments are not contiguous"
        }

        val storedHead = if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(storedHead == null || storedHead <= recovered) {
            "Self-healing head points past durable event tail"
        }
        if (storedHead != recovered) writeHead(recovered)
        return recovered
    }

    private fun segmentRevision(file: File): Long =
        requireNotNull(
            file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
        ) { "Invalid Self-healing event segment name: ${file.name}" }

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
            container = EncryptedLedgerVaultSupport.readAtomic(
                target = file,
                maxPlaintextBytes = maxPlaintextBytes,
            ),
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
        )

    private fun writeEncrypted(file: File, plaintext: ByteArray, maxPlaintextBytes: Int) {
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(
                plaintext = plaintext,
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
            ),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Self-healing vault unavailable" }
        check(incidentsDirectory.isDirectory || incidentsDirectory.mkdirs()) {
            "Self-healing incident directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "self-healing-ledger"
        const val FILE_NAME = "self-healing.sheal"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".sheal"
        const val KEY_ALIAS = "lifeos.self.healing.v1"
        val processMutex = Mutex()
    }
}
