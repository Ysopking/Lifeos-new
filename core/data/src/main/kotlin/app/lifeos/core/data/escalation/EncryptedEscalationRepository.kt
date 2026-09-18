package app.lifeos.core.data.escalation

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.escalation.EscalationRecord
import app.lifeos.core.runtime.escalation.EscalationRecordLogCodec
import app.lifeos.core.runtime.escalation.EscalationRepository
import app.lifeos.core.runtime.escalation.EscalationRepositoryLoadReport
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedEscalationRepository(context: Context) : EscalationRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val recordsDirectory = directory.resolve("records")
    private val headFile = directory.resolve(HEAD_FILE_NAME)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): EscalationRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val unreadable = mutableListOf<String>()
            val records = recordFiles().mapNotNull { file ->
                runCatching { readValidatedRecord(file) }
                    .onFailure { unreadable += file.relativeTo(directory).path }
                    .getOrNull()
            }
            if (unreadable.isEmpty()) {
                readHeadOrRecover()
            }
            EscalationRepositoryLoadReport(
                records = records.sortedBy { it.revision },
                unreadableEntries = unreadable.sorted(),
            )
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        record: EscalationRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            ensureDirectory()
            requireReadableHistory()
            val currentRevision = readHeadOrRecover()
            if (currentRevision != expectedRevision) return@withLock false
            require(record.revision == expectedRevision + 1L) {
                "Escalation append revision mismatch"
            }

            val target = recordFile(record)
            if (exists(target)) {
                require(readValidatedRecord(target) == record) {
                    "Escalation record revision collision"
                }
            } else {
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                writeRecord(target, record)
            }
            writeHead(record.revision)
            true
        }
    }

    private fun readValidatedRecord(file: File): EscalationRecord {
        val record = EscalationRecordLogCodec.decodeSegment(
            decrypt(
                file = file,
                maxPlaintextBytes = EscalationRecordLogCodec.MAX_PAYLOAD_BYTES,
                associatedData = associatedData(file),
            )
        )
        require(record.revision == segmentRevision(file)) {
            "Escalation payload revision does not match segment path"
        }
        val idDirectory = requireNotNull(file.parentFile) {
            "Escalation segment has no escalation directory"
        }
        require(idDirectory.parentFile == recordsDirectory) {
            "Escalation segment is not stored under records directory"
        }
        require(idDirectory.name == sha256(record.escalationId.value)) {
            "Escalation id does not match segment path"
        }
        return record
    }

    private fun requireReadableHistory() {
        recordFiles().forEach(::readValidatedRecord)
    }

    private fun writeRecord(file: File, record: EscalationRecord) {
        writeEncrypted(
            file = file,
            plaintext = EscalationRecordLogCodec.encodeSegment(record),
            maxPlaintextBytes = EscalationRecordLogCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
    }

    private fun recordFile(record: EscalationRecord): File =
        recordsDirectory.resolve(sha256(record.escalationId.value))
            .resolve(RECORD_PREFIX + record.revision.toString().padStart(20, '0') + RECORD_SUFFIX)

    private fun recordFiles(): List<File> {
        ensureDirectory()
        return recordsDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(RECORD_PREFIX) && it.name.endsWith(RECORD_SUFFIX) }
            .sortedBy { it.path }
            .toList()
    }

    private fun readHeadOrRecover(): Long {
        val files = recordFiles()
        val byRevision = files.groupBy(::segmentRevision)
        require(byRevision.values.all { it.size == 1 }) {
            "Escalation ledger contains duplicate global revisions"
        }
        val revisions = byRevision.keys.sorted()
        val recovered = revisions.lastOrNull() ?: 0L
        require(revisions == if (recovered == 0L) emptyList() else (1L..recovered).toList()) {
            "Escalation ledger segments are not contiguous"
        }

        val storedHead = if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(storedHead == null || storedHead <= recovered) {
            "Escalation head points past durable record tail"
        }
        if (storedHead != recovered) writeHead(recovered)
        return recovered
    }

    private fun segmentRevision(file: File): Long =
        requireNotNull(
            file.name.removePrefix(RECORD_PREFIX).removeSuffix(RECORD_SUFFIX).toLongOrNull()
        ) { "Invalid escalation segment name: " + file.name }

    private fun readHead(): Long {
        val bytes = decrypt(
            file = headFile,
            maxPlaintextBytes = 64,
            associatedData = associatedData(headFile),
        )
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).long.also { require(it >= 0L) }
    }

    private fun writeHead(revision: Long) {
        writeEncrypted(
            file = headFile,
            plaintext = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revision).array(),
            maxPlaintextBytes = 64,
            associatedData = associatedData(headFile),
        )
    }

    private fun decrypt(
        file: File,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ): ByteArray = EncryptedLedgerVaultSupport.decrypt(
        container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = maxPlaintextBytes,
        ),
        key = key,
        maxPlaintextBytes = maxPlaintextBytes,
        associatedData = associatedData,
    )

    private fun writeEncrypted(
        file: File,
        plaintext: ByteArray,
        maxPlaintextBytes: Int,
        associatedData: ByteArray,
    ) {
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(
                plaintext = plaintext,
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
                associatedData = associatedData,
            ),
        )
    }

    private fun associatedData(file: File): ByteArray {
        val relative = file.relativeTo(directory).invariantSeparatorsPath
        return ("lifeos-escalation-v1|" + relative).toByteArray(Charsets.UTF_8)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Escalation vault unavailable"
        }
        check(recordsDirectory.isDirectory || recordsDirectory.mkdirs()) {
            "Escalation records directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File(target.path + ".bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "escalation-ledger"
        const val HEAD_FILE_NAME = "head.escalation"
        const val RECORD_PREFIX = "record-"
        const val RECORD_SUFFIX = ".escalation"
        const val KEY_ALIAS = "lifeos.escalation.v1"
        val processMutex = Mutex()
    }
}
