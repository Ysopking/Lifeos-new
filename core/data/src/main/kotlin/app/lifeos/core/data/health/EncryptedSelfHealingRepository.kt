package app.lifeos.core.data.health

import android.content.Context
import app.lifeos.core.data.security.EncryptedCasHeadStore
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.EncryptedSegmentedLedger
import app.lifeos.core.data.security.LedgerLongCodec
import app.lifeos.core.data.security.SegmentPathBinding
import app.lifeos.core.data.security.SegmentRevisionPolicy
import app.lifeos.core.data.security.SegmentRevisionScope
import app.lifeos.core.data.security.segmentKeySha256
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventLogCodec
import app.lifeos.core.runtime.health.SelfHealingRepository
import app.lifeos.core.runtime.health.SelfHealingRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Global self-healing event stream backed by the shared segmented-ledger authority.
 *
 * Incident directories provide physical partitioning only; the revision sequence remains global.
 * The encrypted head is reconstructed from durable segments after interruption or stale-head state.
 */
class EncryptedSelfHealingRepository(context: Context) : SelfHealingRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val incidentsDirectory = directory.resolve("incidents")
    private val headFile = directory.resolve("head.sheal")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }
    private val revisionPolicy = SegmentRevisionPolicy<String, SelfHealingEvent>(
        scope = SegmentRevisionScope.GLOBAL,
        keyOf = { event -> event.incidentId.value },
        revisionOf = { event -> event.revision },
    )
    private val pathBinding = SegmentPathBinding<String>(
        ledgerDomain = "self-healing-ledger/v2",
        segmentsDirectory = incidentsDirectory,
        keyFingerprint = ::segmentKeySha256,
        segmentPrefix = EVENT_PREFIX,
        segmentSuffix = EVENT_SUFFIX,
    )
    private val segmented: EncryptedSegmentedLedger<String, SelfHealingEvent> by lazy {
        EncryptedSegmentedLedger(
            rootDirectory = directory,
            segmentsDirectory = incidentsDirectory,
            key = key,
            maxPlaintextBytes = SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES,
            pathBinding = pathBinding,
            revisionPolicy = revisionPolicy,
            encode = SelfHealingEventLogCodec::encodeSegment,
            decode = SelfHealingEventLogCodec::decodeSegment,
            entryComparator = compareBy<SelfHealingEvent> { it.revision },
        )
    }
    private val headStore: EncryptedCasHeadStore<Long> by lazy {
        EncryptedCasHeadStore(
            file = headFile,
            key = key,
            domain = "self-healing-head/v2",
            maxPlaintextBytes = HEAD_MAX_PLAINTEXT_BYTES,
            encode = LedgerLongCodec::encode,
            decode = LedgerLongCodec::decode,
            validate = { require(it >= 0L) },
        )
    }

    override suspend fun loadReport(): SelfHealingRepositoryLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureMigrated()
                val report = segmented.loadReport()
                if (report.unreadableEntries.isEmpty()) {
                    reconcileHead(report.entries)
                }
                SelfHealingRepositoryLoadReport(
                    events = report.entries,
                    unreadableEntries = report.unreadableEntries,
                )
            }
        }

    override suspend fun append(
        expectedRevision: Long,
        event: SelfHealingEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            val report = segmented.loadReport()
            require(report.unreadableEntries.isEmpty()) {
                "Self-healing ledger contains unreadable segments"
            }
            val currentRevision = reconcileHead(report.entries)
            if (currentRevision != expectedRevision) return@withLock false
            revisionPolicy.requireAppend(expectedRevision, event)
            if (!segmented.append(expectedRevision, event)) return@withLock false
            check(headStore.compareAndSet(expectedRevision, event.revision)) {
                "Self-healing durable head CAS diverged from appended segment"
            }
            true
        }
    }

    private fun ensureMigrated() {
        segmented.migrateIfEmpty {
            if (!exists(legacyFile)) {
                emptyList()
            } else {
                SelfHealingEventLogCodec.decode(
                    decryptLegacy(legacyFile, SelfHealingEventLogCodec.MAX_PAYLOAD_BYTES)
                )
            }
        }
    }

    private fun reconcileHead(events: List<SelfHealingEvent>): Long {
        revisionPolicy.validateHistory(events)
        val recovered = events.lastOrNull()?.revision ?: 0L
        val stored = if (headStore.exists()) runCatching { headStore.load() }.getOrNull() else null
        require(stored == null || stored <= recovered) {
            "Self-healing head points past durable event tail"
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
        const val ROOT_DIRECTORY = "self-healing-ledger"
        const val FILE_NAME = "self-healing.sheal"
        const val EVENT_PREFIX = "event-"
        const val EVENT_SUFFIX = ".sheal"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val KEY_ALIAS = "lifeos.self.healing.v1"
        const val HEAD_MAX_PLAINTEXT_BYTES = 64
        val processMutex = Mutex()
    }
}
