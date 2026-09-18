package app.lifeos.core.data.deepsearch

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEvent
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventLogCodec
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepository
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-mission encrypted V12 event segments with a tiny global revision head. */
class EncryptedDeepSearchMissionRepository(context: Context) : DeepSearchMissionRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val missionsDirectory = directory.resolve("missions")
    private val headFile = directory.resolve("head.dsmission")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): DeepSearchMissionRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            readHeadOrRecover()
            val unreadable = mutableListOf<String>()
            val events = eventFiles().mapNotNull { file ->
                runCatching { readEvent(file) }
                    .onFailure { unreadable += file.relativeTo(directory).path }
                    .getOrNull()
            }
            DeepSearchMissionRepositoryLoadReport(events.sortedBy { it.revision }, unreadable.sorted())
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: DeepSearchMissionEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            val currentRevision = readHeadOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "DeepSearch mission append revision mismatch"
            }
            val target = eventFile(event)
            if (exists(target)) {
                require(readEvent(target) == event) { "DeepSearch mission event collision" }
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
        val legacy = DeepSearchMissionEventLogCodec.decode(
            decrypt(legacyFile, DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        legacy.sortedBy { it.revision }.forEachIndexed { index, event ->
            require(event.revision == index.toLong() + 1L) {
                "Legacy DeepSearch revisions are not contiguous"
            }
            writeEvent(eventFile(event), event)
        }
        writeHead(legacy.lastOrNull()?.revision ?: 0L)
    }

    private fun readEvent(file: File): DeepSearchMissionEvent {
        val events = DeepSearchMissionEventLogCodec.decode(
            decrypt(file, DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES)
        )
        require(events.size == 1) { "DeepSearch segment must contain one event" }
        return events.single()
    }

    private fun writeEvent(file: File, event: DeepSearchMissionEvent) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        writeEncrypted(
            file,
            DeepSearchMissionEventLogCodec.encode(listOf(event)),
            DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun eventFile(event: DeepSearchMissionEvent): File =
        missionsDirectory.resolve(sha256(event.missionId.value))
            .resolve("$EVENT_PREFIX${event.revision.toString().padStart(20, '0')}$EVENT_SUFFIX")

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return missionsDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(EVENT_PREFIX) && it.name.endsWith(EVENT_SUFFIX) }
            .sortedBy { it.path }
            .toList()
    }

    private fun readHeadOrRecover(): Long {
        val files = eventFiles()
        val byRevision = files.groupBy { file ->
            requireNotNull(
                file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
            ) { "Invalid DeepSearch event segment name: ${file.name}" }
        }
        require(byRevision.values.all { it.size == 1 }) {
            "DeepSearch contains duplicate global event revisions"
        }
        val revisions = byRevision.keys.sorted()
        val recovered = revisions.lastOrNull() ?: 0L
        require(revisions == if (recovered == 0L) emptyList() else (1L..recovered).toList()) {
            "DeepSearch event segments are not contiguous"
        }

        val storedHead = if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(storedHead == null || storedHead <= recovered) {
            "DeepSearch head points past durable event tail"
        }
        if (storedHead != recovered) writeHead(recovered)
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
        check(directory.isDirectory || directory.mkdirs()) { "DeepSearch mission vault unavailable" }
        check(missionsDirectory.isDirectory || missionsDirectory.mkdirs()) {
            "DeepSearch mission segment directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "deep-search-v2"
        const val FILE_NAME = "missions.dsmission"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".dsmission"
        const val KEY_ALIAS = "lifeos.deep.search.mission.v2"
        val processMutex = Mutex()
    }
}
