package app.lifeos.core.data.trace

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.EncryptedSegmentedLedger
import app.lifeos.core.data.security.SegmentPathBinding
import app.lifeos.core.data.security.SegmentRevisionPolicy
import app.lifeos.core.data.security.SegmentRevisionScope
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceLogCodec
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted per-trace revision ledger backed by the shared segmented-ledger authority.
 *
 * Runtime consumers keep the existing [DecisionTraceRepository] contract; storage identity,
 * revision continuity, AtomicFile recovery and physical-path AAD binding are owned centrally.
 */
class EncryptedDecisionTraceRepository(context: Context) : DecisionTraceRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val tracesDirectory = directory.resolve("traces")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }
    private val revisionPolicy = SegmentRevisionPolicy<String, DecisionTrace>(
        scope = SegmentRevisionScope.PER_KEY,
        keyOf = { trace -> trace.id.value },
        revisionOf = { trace -> trace.revision },
    )
    private val pathBinding = SegmentPathBinding<String>(
        ledgerDomain = "decision-trace-ledger/v2",
        segmentsDirectory = tracesDirectory,
        keyFingerprint = SegmentPathBinding::sha256,
        segmentPrefix = REVISION_PREFIX,
        segmentSuffix = REVISION_SUFFIX,
    )
    private val segmented: EncryptedSegmentedLedger<String, DecisionTrace> by lazy {
        EncryptedSegmentedLedger(
            rootDirectory = directory,
            segmentsDirectory = tracesDirectory,
            key = key,
            maxPlaintextBytes = DecisionTraceLogCodec.MAX_PAYLOAD_BYTES,
            pathBinding = pathBinding,
            revisionPolicy = revisionPolicy,
            encode = DecisionTraceLogCodec::encodeSegment,
            decode = DecisionTraceLogCodec::decodeSegment,
            entryComparator = compareBy<DecisionTrace> { it.id.value }.thenBy { it.revision },
        )
    }

    override suspend fun loadReport(): DecisionTraceRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val report = segmented.loadReport()
            DecisionTraceRepositoryLoadReport(
                traces = report.entries,
                unreadableEntries = report.unreadableEntries,
            )
        }
    }

    override suspend fun save(
        expectedRevision: Long,
        trace: DecisionTrace,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureMigrated()
            segmented.append(expectedRevision, trace)
        }
    }

    private fun ensureMigrated() {
        segmented.migrateIfEmpty {
            if (!exists(legacyFile)) {
                emptyList()
            } else {
                DecisionTraceLogCodec.decode(
                    decryptLegacy(legacyFile, DecisionTraceLogCodec.MAX_PAYLOAD_BYTES)
                )
            }
        }
    }

    private fun decryptLegacy(file: File, maxPlaintextBytes: Int): ByteArray =
        EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(
                target = file,
                maxPlaintextBytes = maxPlaintextBytes,
            ),
            key = key,
            maxPlaintextBytes = maxPlaintextBytes,
        )

    private fun exists(target: File): Boolean =
        target.exists() || File(target.path + ATOMIC_BACKUP_SUFFIX).exists()

    private companion object {
        const val ROOT_DIRECTORY = "decision-trace-ledger"
        const val FILE_NAME = "decision-traces.dtrace"
        const val REVISION_PREFIX = "revision-"
        const val REVISION_SUFFIX = ".dtrace"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val KEY_ALIAS = "lifeos.decision.trace.v1"
        val processMutex = Mutex()
    }
}
