package app.lifeos.core.data.policy

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyEventLogCodec
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Segmented encrypted owner-policy ledger.
 *
 * Each revision is an immutable encrypted event file. A tiny encrypted head provides the hot path.
 * The previous monolithic file is accepted once as a migration source and remains recovery-only.
 */
class EncryptedOwnerPolicyRepository(context: Context) : OwnerPolicyRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val eventsDirectory = directory.resolve(EVENTS_DIRECTORY)
    private val headFile = directory.resolve(HEAD_FILE)
    private val legacyFile = directory.resolve(LEGACY_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            readHeadStrictOrRecover()
            val unreadable = mutableListOf<String>()
            val events = eventFiles().mapNotNull { file ->
                try {
                    readEvent(file)
                } catch (_: Exception) {
                    unreadable += file.name
                    null
                }
            }
            OwnerPolicyRepositoryLoadReport(
                events = events.sortedBy { it.revision },
                unreadableEntries = unreadable.sorted(),
            )
        }
    }

    override suspend fun headRevision(): Long = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            readHeadStrictOrRecover()
        }
    }

    override suspend fun loadAfter(revisionExclusive: Long): List<OwnerPolicyEvent> =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                require(revisionExclusive >= 0L)
                ensureMigrated()
                val head = readHeadStrictOrRecover()
                if (revisionExclusive >= head) return@withLock emptyList()
                ((revisionExclusive + 1L)..head).map { revision ->
                    val file = eventFile(revision)
                    check(exists(file)) { "Missing owner-policy event revision $revision" }
                    readEvent(file)
                }
            }
        }

    override suspend fun append(
        expectedRevision: Long,
        event: OwnerPolicyEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            val currentRevision = readHeadStrictOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "Owner policy append revision mismatch"
            }
            val target = eventFile(event.revision)
            if (exists(target)) {
                check(readEvent(target) == event) {
                    "Owner policy revision identity collision"
                }
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
        val legacy = readLegacyStrict()
        legacy.sortedBy { it.revision }.forEachIndexed { index, event ->
            require(event.revision == index.toLong() + 1L) {
                "Legacy owner-policy revisions are not contiguous"
            }
            writeEvent(eventFile(event.revision), event)
        }
        writeHead(legacy.lastOrNull()?.revision ?: 0L)
    }

    private fun readHeadStrictOrRecover(): Long {
        val revisions = eventFiles().map { file ->
            requireNotNull(
                file.name.removePrefix(EVENT_PREFIX).removeSuffix(EVENT_SUFFIX).toLongOrNull()
            ) { "Invalid owner-policy event segment name: ${file.name}" }
        }.sorted()
        val recovered = revisions.lastOrNull() ?: 0L
        require(revisions == if (recovered == 0L) emptyList() else (1L..recovered).toList()) {
            "Owner policy event segments are not contiguous"
        }

        val storedHead = if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(storedHead == null || storedHead <= recovered) {
            "Owner policy head points past durable event tail"
        }
        if (storedHead != recovered) writeHead(recovered)
        return recovered
    }

    private fun readEvent(file: File): OwnerPolicyEvent {
        val plaintext = decrypt(
            file = file,
            maxPlaintextBytes = OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val events = OwnerPolicyEventLogCodec.decode(plaintext)
        require(events.size == 1) { "Owner policy segment must contain exactly one event" }
        val event = events.single()
        require(file == eventFile(event.revision)) {
            "Owner policy event segment/revision mismatch"
        }
        return event
    }

    private fun writeEvent(file: File, event: OwnerPolicyEvent) {
        val plaintext = OwnerPolicyEventLogCodec.encode(listOf(event))
        writeEncrypted(file, plaintext, OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES)
    }

    private fun readLegacyStrict(): List<OwnerPolicyEvent> =
        OwnerPolicyEventLogCodec.decode(
            decrypt(
                file = legacyFile,
                maxPlaintextBytes = OwnerPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
            )
        )

    private fun readHead(): Long {
        val bytes = decrypt(headFile, HEAD_PLAINTEXT_BYTES)
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).long.also { require(it >= 0L) }
    }

    private fun writeHead(revision: Long) {
        require(revision >= 0L)
        writeEncrypted(
            headFile,
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revision).array(),
            HEAD_PLAINTEXT_BYTES,
        )
    }

    private fun decrypt(file: File, maxPlaintextBytes: Int): ByteArray {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = maxPlaintextBytes,
        )
        return EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
        )
    }

    private fun writeEncrypted(file: File, plaintext: ByteArray, maxPlaintextBytes: Int) {
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return eventsDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(EVENT_PREFIX) && it.name.endsWith(EVENT_SUFFIX) }
            .sortedBy { it.name }
    }

    private fun eventFile(revision: Long): File =
        eventsDirectory.resolve("$EVENT_PREFIX${revision.toString().padStart(20, '0')}$EVENT_SUFFIX")

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Owner policy vault unavailable" }
        check(eventsDirectory.isDirectory || eventsDirectory.mkdirs()) {
            "Owner policy event directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "owner-policy-ledger"
        const val EVENTS_DIRECTORY = "events"
        const val HEAD_FILE = "head.opolicy"
        const val LEGACY_FILE = "owner-policy.opolicy"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".opolicy"
        const val KEY_ALIAS = "lifeos.owner.policy.v1"
        const val HEAD_PLAINTEXT_BYTES = 64
        val processMutex = Mutex()
    }
}
