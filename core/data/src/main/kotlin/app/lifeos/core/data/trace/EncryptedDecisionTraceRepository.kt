package app.lifeos.core.data.trace

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceLogCodec
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted decision traces stored as immutable per-trace revision segments. */
class EncryptedDecisionTraceRepository(context: Context) : DecisionTraceRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val tracesDirectory = directory.resolve("traces")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): DecisionTraceRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val unreadable = mutableListOf<String>()
            val traces = traceFiles().mapNotNull { file ->
                runCatching { readTrace(file) }
                    .onFailure { unreadable += file.relativeTo(directory).path }
                    .getOrNull()
            }
            DecisionTraceRepositoryLoadReport(
                traces = traces.sortedWith(compareBy<DecisionTrace> { it.id.value }.thenBy { it.revision }),
                unreadableEntries = unreadable.sorted(),
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
            val traceDirectory = traceDirectory(trace.id.value)
            val currentRevision = traceDirectory.listFiles().orEmpty()
                .filter { it.name.startsWith(REVISION_PREFIX) && it.name.endsWith(REVISION_SUFFIX) }
                .mapNotNull { it.name.removePrefix(REVISION_PREFIX).removeSuffix(REVISION_SUFFIX).toLongOrNull() }
                .maxOrNull() ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            require(trace.revision == expectedRevision + 1L) {
                "Decision trace append revision mismatch"
            }
            val target = revisionFile(trace.id.value, trace.revision)
            if (exists(target)) {
                require(readTrace(target) == trace) { "Decision trace revision collision" }
            } else {
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                writeTrace(target, trace)
            }
            true
        }
    }

    private fun ensureMigrated() {
        ensureDirectory()
        if (traceFiles().isNotEmpty()) return
        if (!exists(legacyFile)) return
        val legacy = DecisionTraceLogCodec.decode(
            decrypt(legacyFile, DecisionTraceLogCodec.MAX_PAYLOAD_BYTES)
        )
        legacy.forEach { trace ->
            val target = revisionFile(trace.id.value, trace.revision)
            if (!exists(target)) {
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                writeTrace(target, trace)
            }
        }
    }

    private fun readTrace(file: File): DecisionTrace {
        val values = DecisionTraceLogCodec.decode(
            decrypt(file, DecisionTraceLogCodec.MAX_PAYLOAD_BYTES)
        )
        require(values.size == 1) { "Decision trace segment must contain one trace revision" }
        return values.single()
    }

    private fun writeTrace(file: File, trace: DecisionTrace) {
        writeEncrypted(
            file,
            DecisionTraceLogCodec.encode(listOf(trace)),
            DecisionTraceLogCodec.MAX_PAYLOAD_BYTES,
        )
    }

    private fun traceDirectory(traceId: String): File =
        tracesDirectory.resolve(sha256(traceId))

    private fun revisionFile(traceId: String, revision: Long): File =
        traceDirectory(traceId).resolve(
            "$REVISION_PREFIX${revision.toString().padStart(20, '0')}$REVISION_SUFFIX"
        )

    private fun traceFiles(): List<File> {
        ensureDirectory()
        return tracesDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(REVISION_PREFIX) && it.name.endsWith(REVISION_SUFFIX) }
            .sortedBy { it.path }
            .toList()
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
        check(directory.isDirectory || directory.mkdirs()) { "Decision trace vault unavailable" }
        check(tracesDirectory.isDirectory || tracesDirectory.mkdirs()) {
            "Decision trace segment directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "decision-trace-ledger"
        const val FILE_NAME = "decision-traces.dtrace"
        const val REVISION_PREFIX = "revision-"
        const val REVISION_SUFFIX = ".dtrace"
        const val KEY_ALIAS = "lifeos.decision.trace.v1"
        val processMutex = Mutex()
    }
}
