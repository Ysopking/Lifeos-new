package app.lifeos.core.data.policy

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEvent
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEventLogCodec
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepository
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Segmented encrypted append-only Owner Observation Policy ledger. */
class EncryptedOwnerObservationPolicyRepository(
    context: Context,
) : OwnerObservationPolicyRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val eventsDirectory = directory.resolve(EVENTS_DIRECTORY)
    private val headFile = directory.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): OwnerObservationPolicyRepositoryLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
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
                OwnerObservationPolicyRepositoryLoadReport(
                    events = events.sortedBy { it.revision },
                    unreadableEntries = unreadable.sorted(),
                )
            }
        }

    override suspend fun headRevision(): Long = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            readHeadStrictOrRecover()
        }
    }

    override suspend fun loadAfter(
        revisionExclusive: Long,
    ): List<OwnerObservationPolicyEvent> = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(revisionExclusive >= 0L)
            ensureDirectory()
            val head = readHeadStrictOrRecover()
            if (revisionExclusive >= head) return@withLock emptyList()
            ((revisionExclusive + 1L)..head).map { revision ->
                val file = eventFile(revision)
                check(exists(file)) {
                    "Missing owner observation policy revision $revision"
                }
                readEvent(file)
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: OwnerObservationPolicyEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val currentRevision = readHeadStrictOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "Owner observation policy append revision mismatch"
            }
            val target = eventFile(event.revision)
            if (exists(target)) {
                check(readEvent(target) == event) {
                    "Owner observation policy revision identity collision"
                }
            } else {
                writeEvent(target, event)
            }
            writeHead(event.revision)
            true
        }
    }

    private fun readHeadStrictOrRecover(): Long {
        val revisions = eventFiles().map { file ->
            requireNotNull(
                file.name
                    .removePrefix(EVENT_PREFIX)
                    .removeSuffix(EVENT_SUFFIX)
                    .toLongOrNull()
            ) {
                "Invalid owner observation policy segment name: ${file.name}"
            }
        }.sorted()
        val recovered = revisions.lastOrNull() ?: 0L
        require(
            revisions == if (recovered == 0L) emptyList() else (1L..recovered).toList()
        ) {
            "Owner observation policy event segments are not contiguous"
        }

        val storedHead =
            if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(storedHead == null || storedHead <= recovered) {
            "Owner observation policy head points past durable event tail"
        }
        if (storedHead != recovered) writeHead(recovered)
        return recovered
    }

    private fun readEvent(file: File): OwnerObservationPolicyEvent {
        val decrypted = decrypt(
            file = file,
            maxPlaintextBytes = OwnerObservationPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val event =
            OwnerObservationPolicyEventLogCodec.decodeSegment(decrypted.plaintext)
        require(file == eventFile(event.revision)) {
            "Owner observation policy segment/revision mismatch"
        }
        if (decrypted.migratedFromUnboundLegacy) {
            writeEncrypted(
                file,
                decrypted.plaintext,
                OwnerObservationPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
            )
        }
        return event
    }

    private fun writeEvent(
        file: File,
        event: OwnerObservationPolicyEvent,
    ) {
        writeEncrypted(
            file,
            OwnerObservationPolicyEventLogCodec.encodeSegment(event),
            OwnerObservationPolicyEventLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun readHead(): Long {
        val decrypted = decrypt(headFile, HEAD_PLAINTEXT_BYTES)
        require(decrypted.plaintext.size == Long.SIZE_BYTES)
        val revision = ByteBuffer.wrap(decrypted.plaintext).long.also {
            require(it >= 0L)
        }
        if (decrypted.migratedFromUnboundLegacy) writeHead(revision)
        return revision
    }

    private fun writeHead(revision: Long) {
        require(revision >= 0L)
        writeEncrypted(
            headFile,
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revision).array(),
            HEAD_PLAINTEXT_BYTES,
        )
    }

    private fun decrypt(file: File, maxPlaintextBytes: Int) =
        EncryptedLedgerVaultSupport.decryptPathBoundOrLegacy(
            container = EncryptedLedgerVaultSupport.readAtomic(
                target = file,
                maxPlaintextBytes = maxPlaintextBytes,
            ),
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
            associatedData = associatedData(file),
        )

    private fun writeEncrypted(
        file: File,
        plaintext: ByteArray,
        maxPlaintextBytes: Int,
    ) {
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun associatedData(file: File): ByteArray =
        VaultAssociatedData.forPath(
            "owner-observation-policy/v1",
            directory,
            file,
        )

    private fun eventFiles(): List<File> {
        ensureDirectory()
        return eventsDirectory.listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith(EVENT_PREFIX) &&
                    it.name.endsWith(EVENT_SUFFIX)
            }
            .sortedBy { it.name }
    }

    private fun eventFile(revision: Long): File =
        eventsDirectory.resolve(
            "$EVENT_PREFIX${revision.toString().padStart(20, '0')}$EVENT_SUFFIX"
        )

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Owner observation policy vault unavailable"
        }
        check(eventsDirectory.isDirectory || eventsDirectory.mkdirs()) {
            "Owner observation policy event directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "owner-observation-policy-ledger"
        const val EVENTS_DIRECTORY = "events"
        const val HEAD_FILE = "head.oobserve"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".oobserve"
        const val KEY_ALIAS = "lifeos.owner.observation.policy.v1"
        const val HEAD_PLAINTEXT_BYTES = 64
        val processMutex = Mutex()
    }
}
