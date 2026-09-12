package app.lifeos.core.data.trace

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
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
 * Encrypted append-only V15 decision-trace repository. Trace revisions are retained so restart
 * reconstruction can prove the exact durable chain instead of trusting a mutable latest snapshot.
 */
class EncryptedDecisionTraceRepository(context: Context) : DecisionTraceRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): DecisionTraceRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock DecisionTraceRepositoryLoadReport(emptyList())
            try {
                DecisionTraceRepositoryLoadReport(readStrict())
            } catch (_: Exception) {
                DecisionTraceRepositoryLoadReport(
                    traces = emptyList(),
                    unreadableEntries = listOf(FILE_NAME),
                )
            }
        }
    }

    override suspend fun save(
        expectedRevision: Long,
        trace: DecisionTrace,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            val traces = if (exists(file)) readStrict() else emptyList()
            val currentRevision = traces.asSequence()
                .filter { it.id == trace.id }
                .maxOfOrNull { it.revision }
                ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(trace.revision == expectedRevision + 1L) {
                "Decision trace append revision mismatch"
            }
            write(traces + trace)
            true
        }
    }

    private fun readStrict(): List<DecisionTrace> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = DecisionTraceLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = DecisionTraceLogCodec.MAX_PAYLOAD_BYTES,
        )
        return DecisionTraceLogCodec.decode(plaintext)
    }

    private fun write(traces: List<DecisionTrace>) {
        val plaintext = DecisionTraceLogCodec.encode(traces)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = DecisionTraceLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Decision trace vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "decision-trace-ledger"
        const val FILE_NAME = "decision-traces.dtrace"
        const val KEY_ALIAS = "lifeos.decision.trace.v1"
        val processMutex = Mutex()
    }
}
