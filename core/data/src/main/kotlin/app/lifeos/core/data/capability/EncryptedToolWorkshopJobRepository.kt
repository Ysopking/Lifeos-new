package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedCasHeadStore
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.EncryptedSegmentedLedger
import app.lifeos.core.data.security.LedgerLongCodec
import app.lifeos.core.data.security.SegmentPathBinding
import app.lifeos.core.data.security.SegmentRevisionPolicy
import app.lifeos.core.data.security.SegmentRevisionScope
import app.lifeos.core.data.security.segmentKeySha256
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

/**
 * Global-revision ToolWorkshop ledger on the shared segmented persistence core.
 *
 * Event segments are keyed by deterministic job id while the revision stream remains global.
 * The encrypted head is a recoverable acceleration structure, never a second source of truth.
 */
class EncryptedToolWorkshopJobRepository(context: Context) : ToolWorkshopJobRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val jobsDirectory = directory.resolve("jobs")
    private val headFile = directory.resolve("head.twj")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }
    private val revisionPolicy = SegmentRevisionPolicy<String, ToolWorkshopJobEvent>(
        scope = SegmentRevisionScope.GLOBAL,
        keyOf = { event -> event.definition.id.value },
        revisionOf = { event -> event.revision },
    )
    private val pathBinding = SegmentPathBinding<String>(
        ledgerDomain = "tool-workshop-job-ledger/v2",
        segmentsDirectory = jobsDirectory,
        keyFingerprint = ::segmentKeySha256,
        segmentPrefix = EVENT_PREFIX,
        segmentSuffix = EVENT_SUFFIX,
    )
    private val segmented: EncryptedSegmentedLedger<String, ToolWorkshopJobEvent> by lazy {
        EncryptedSegmentedLedger(
            rootDirectory = directory,
            segmentsDirectory = jobsDirectory,
            key = key,
            maxPlaintextBytes = ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES,
            pathBinding = pathBinding,
            revisionPolicy = revisionPolicy,
            encode = ToolWorkshopJobEventLogCodec::encodeSegment,
            decode = ToolWorkshopJobEventLogCodec::decodeSegment,
            entryComparator = compareBy<ToolWorkshopJobEvent> { it.revision },
        )
    }
    private val headStore: EncryptedCasHeadStore<Long> by lazy {
        EncryptedCasHeadStore(
            file = headFile,
            key = key,
            domain = "tool-workshop-job-head/v2",
            maxPlaintextBytes = HEAD_MAX_PLAINTEXT_BYTES,
            encode = LedgerLongCodec::encode,
            decode = LedgerLongCodec::decode,
            validate = { require(it >= 0L) },
        )
    }

    override suspend fun loadReport(): ToolWorkshopJobRepositoryLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureMigrated()
                val report = segmented.loadReport()
                if (report.unreadableEntries.isEmpty()) {
                    reconcileHead(report.entries)
                }
                ToolWorkshopJobRepositoryLoadReport(
                    events = report.entries,
                    unreadableEntries = report.unreadableEntries,
                )
            }
        }

    override suspend fun append(
        expectedRevision: Long,
        event: ToolWorkshopJobEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            val report = segmented.loadReport()
            require(report.unreadableEntries.isEmpty()) {
                "ToolWorkshop job ledger contains unreadable segments"
            }
            val currentRevision = reconcileHead(report.entries)
            if (currentRevision != expectedRevision) return@withLock false
            revisionPolicy.requireAppend(expectedRevision, event)
            if (!segmented.append(expectedRevision, event)) return@withLock false
            check(headStore.compareAndSet(expectedRevision, event.revision)) {
                "ToolWorkshop durable head CAS diverged from appended segment"
            }
            true
        }
    }

    private fun ensureMigrated() {
        segmented.migrateIfEmpty {
            if (!exists(legacyFile)) {
                emptyList()
            } else {
                ToolWorkshopJobEventLogCodec.decode(
                    decryptLegacy(legacyFile, ToolWorkshopJobEventLogCodec.MAX_PAYLOAD_BYTES)
                )
            }
        }
    }

    private fun reconcileHead(events: List<ToolWorkshopJobEvent>): Long {
        revisionPolicy.validateHistory(events)
        val recovered = events.lastOrNull()?.revision ?: 0L
        val stored = if (headStore.exists()) runCatching { headStore.load() }.getOrNull() else null
        require(stored == null || stored <= recovered) {
            "ToolWorkshop head points past durable event tail"
        }
        if (stored != recovered) headStore.write(recovered)
        return recovered
    }

    private fun decryptLegacy(file: File, maxPlaintextBytes: Int): ByteArray =
        EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, maxPlaintextBytes),
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
        )

    private fun exists(target: File): Boolean =
        target.exists() || File(target.path + ATOMIC_BACKUP_SUFFIX).exists()

    private companion object {
        const val ROOT_DIRECTORY = "tool-workshop-job-ledger"
        const val FILE_NAME = "tool-workshop.twj"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".twj"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val KEY_ALIAS = "lifeos.tool.workshop.job.v1"
        const val HEAD_MAX_PLAINTEXT_BYTES = 64
        val processMutex = Mutex()
    }
}
