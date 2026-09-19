package app.lifeos.core.data.photon

import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import java.io.File
import javax.crypto.SecretKey

internal data class PhotonIndexJournalStats(
    val segmentCount: Int,
    val totalBytes: Long,
)

internal class EncryptedPhotonIndexJournal(
    rootDirectory: File,
    private val key: SecretKey,
) {
    private val rootDirectory = rootDirectory.canonicalFile
    private val journalDirectory = this.rootDirectory.resolve(JOURNAL_DIRECTORY)

    init {
        ensureDirectory()
    }

    fun append(delta: PhotonIndexDelta) {
        ensureDirectory()
        val target = segmentFile(delta.sequence)
        require(!exists(target)) {
            "Photon index journal sequence already exists: ${delta.sequence}"
        }
        val plaintext = PhotonIndexDeltaCodec.encode(delta)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_DELTA_PLAINTEXT_BYTES,
            associatedData = associatedData(
                target = target,
                sequence = delta.sequence,
                snapshotGeneration = delta.snapshotGeneration,
                snapshotFingerprint = delta.snapshotFingerprint,
            ),
        )
        EncryptedLedgerVaultSupport.atomicWrite(target, encrypted)
    }

    fun loadAfter(
        sequence: Long,
        snapshotGeneration: Long,
        snapshotFingerprint: String,
    ): List<PhotonIndexDelta> {
        require(sequence >= 0L)
        require(snapshotGeneration >= 0L)
        require(snapshotFingerprint.matches(Regex("[0-9a-f]{64}")))
        ensureDirectory()

        val segments = segmentFiles()
            .map { file -> parseSequence(file) to file }
            .filter { (value, _) -> value > sequence }
            .sortedBy { it.first }

        var expected = Math.addExact(sequence, 1L)
        return segments.map { (value, file) ->
            require(value == expected) {
                "Photon index journal sequence gap: expected $expected but found $value"
            }
            val container = EncryptedLedgerVaultSupport.readAtomic(
                target = file,
                maxPlaintextBytes = MAX_DELTA_PLAINTEXT_BYTES,
            )
            val plaintext = EncryptedLedgerVaultSupport.decrypt(
                container = container,
                key = key,
                maxPlaintextBytes = MAX_DELTA_PLAINTEXT_BYTES,
                associatedData = associatedData(
                    target = file,
                    sequence = value,
                    snapshotGeneration = snapshotGeneration,
                    snapshotFingerprint = snapshotFingerprint,
                ),
            )
            val delta = PhotonIndexDeltaCodec.decode(plaintext)
            require(delta.sequence == value) {
                "Photon index journal filename/payload sequence mismatch"
            }
            require(delta.snapshotGeneration == snapshotGeneration) {
                "Photon index journal snapshot generation mismatch"
            }
            require(delta.snapshotFingerprint == snapshotFingerprint) {
                "Photon index journal snapshot fingerprint mismatch"
            }
            expected = Math.addExact(expected, 1L)
            delta
        }
    }

    fun stats(): PhotonIndexJournalStats {
        val segments = segmentFiles()
        return PhotonIndexJournalStats(
            segmentCount = segments.size,
            totalBytes = segments.sumOf { physicalLength(it) },
        )
    }

    fun deleteThrough(sequence: Long) {
        require(sequence >= 0L)
        segmentFiles().forEach { file ->
            if (parseSequence(file) <= sequence) {
                android.util.AtomicFile(file).delete()
            }
        }
    }

    fun clear() {
        ensureDirectory()
        journalDirectory.listFiles().orEmpty().forEach { file ->
            val target = if (file.name.endsWith(".bak")) {
                File(file.path.removeSuffix(".bak"))
            } else {
                file
            }
            if (
                target.name.startsWith("delta-") &&
                target.name.endsWith(SEGMENT_SUFFIX)
            ) {
                android.util.AtomicFile(target).delete()
            }
        }
    }

    private fun associatedData(
        target: File,
        sequence: Long,
        snapshotGeneration: Long,
        snapshotFingerprint: String,
    ): ByteArray = buildString {
        append("lifeos-photon-index-journal/v1")
        append('|')
        append(target.canonicalFile.path)
        append('|')
        append(sequence)
        append('|')
        append(snapshotGeneration)
        append('|')
        append(snapshotFingerprint)
    }.toByteArray(Charsets.UTF_8)

    private fun segmentFile(sequence: Long): File {
        require(sequence > 0L && sequence.toString().length <= SEQUENCE_WIDTH) {
            "Photon index journal sequence exceeds physical filename width"
        }
        return journalDirectory.resolve(
            "delta-${sequence.toString().padStart(SEQUENCE_WIDTH, '0')}$SEGMENT_SUFFIX"
        )
    }

    private fun segmentFiles(): List<File> {
        ensureDirectory()
        return journalDirectory.listFiles().orEmpty()
            .map { file ->
                if (file.name.endsWith(".bak")) {
                    File(file.path.removeSuffix(".bak"))
                } else {
                    file
                }
            }
            .filter { it.name.startsWith("delta-") && it.name.endsWith(SEGMENT_SUFFIX) }
            .distinctBy { it.canonicalPath }
            .sortedBy(::parseSequence)
    }

    private fun parseSequence(file: File): Long {
        val raw = file.name
            .removePrefix("delta-")
            .removeSuffix(SEGMENT_SUFFIX)
        require(raw.length == SEQUENCE_WIDTH && raw.all(Char::isDigit)) {
            "Malformed Photon index journal segment name"
        }
        return raw.toLong().also { require(it > 0L) }
    }

    private fun physicalLength(file: File): Long = when {
        file.exists() -> file.length()
        File("${file.path}.bak").exists() -> File("${file.path}.bak").length()
        else -> 0L
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun ensureDirectory() {
        check(journalDirectory.isDirectory || journalDirectory.mkdirs()) {
            "Photon index journal directory unavailable"
        }
    }

    private companion object {
        const val JOURNAL_DIRECTORY = "index-journal"
        const val SEGMENT_SUFFIX = ".pidx"
        const val SEQUENCE_WIDTH = 16
        const val MAX_DELTA_PLAINTEXT_BYTES = 512 * 1024
    }
}
