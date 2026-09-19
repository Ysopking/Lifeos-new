package app.lifeos.core.data.photon

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

internal object PhotonIndexDeltaCodec {
    private const val MAGIC = 0x50494458
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    private const val MAX_TAGS = 10_000

    fun encode(delta: PhotonIndexDelta): ByteArray =
        ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(delta.sequence)
                out.writeInt(delta.operation.ordinal)
                out.writeLong(delta.snapshotGeneration)
                out.text(delta.snapshotFingerprint)
                out.text(delta.ref.photonId.value)
                out.writeLong(delta.ref.revision)
                out.writeBoolean(delta.previousHeadRef != null)
                delta.previousHeadRef?.let { previous ->
                    out.text(previous.photonId.value)
                    out.writeLong(previous.revision)
                }
                out.writeEntry(delta.newEntry)
            }
            bytes.toByteArray()
        }

    fun decode(bytes: ByteArray): PhotonIndexDelta =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid Photon index delta magic" }
            require(input.readInt() == VERSION) { "Unsupported Photon index delta version" }
            val sequence = input.readLong()
            val operationOrdinal = input.readInt()
            require(operationOrdinal in PhotonIndexDeltaOperation.entries.indices) {
                "Invalid Photon index delta operation"
            }
            val operation = PhotonIndexDeltaOperation.entries[operationOrdinal]
            val snapshotGeneration = input.readLong()
            val snapshotFingerprint = input.text()
            val ref = PhotonRevisionRef(
                photonId = PhotonId(input.text()),
                revision = input.readLong(),
            )
            val previous = if (input.readBoolean()) {
                PhotonRevisionRef(
                    photonId = PhotonId(input.text()),
                    revision = input.readLong(),
                )
            } else {
                null
            }
            val entry = input.readEntry()
            require(input.available() == 0) { "Trailing Photon index delta bytes" }
            PhotonIndexDelta(
                sequence = sequence,
                operation = operation,
                ref = ref,
                previousHeadRef = previous,
                newEntry = entry,
                snapshotGeneration = snapshotGeneration,
                snapshotFingerprint = snapshotFingerprint,
            )
        }

    private fun DataOutputStream.writeEntry(entry: PhotonIndexEntry) {
        text(entry.ref.photonId.value)
        writeLong(entry.ref.revision)
        writeLong(entry.createdAt.epochSecond)
        writeInt(entry.createdAt.nano)
        text(entry.phase.name)
        text(entry.mimeType)
        writeInt(entry.tags.size)
        entry.tags.sorted().forEach { text(it) }
        writeDouble(entry.semanticMass)
        writeDouble(entry.confidence)
        text(entry.contentFingerprint)
        writeBoolean(entry.latest)
        writeBoolean(entry.tombstoned)
    }

    private fun DataInputStream.readEntry(): PhotonIndexEntry {
        val ref = PhotonRevisionRef(
            photonId = PhotonId(text()),
            revision = readLong(),
        )
        val createdAt = Instant.ofEpochSecond(
            readLong(),
            readInt().also { require(it in 0..999_999_999) }.toLong(),
        )
        val phase = PhotonPhase.valueOf(text())
        val mimeType = text()
        val tagCount = readInt().also { require(it in 0..MAX_TAGS) }
        val tags = linkedSetOf<String>()
        repeat(tagCount) { tags += text() }
        return PhotonIndexEntry(
            ref = ref,
            createdAt = createdAt,
            phase = phase,
            mimeType = mimeType,
            tags = tags,
            semanticMass = readDouble(),
            confidence = readDouble(),
            contentFingerprint = text(),
            latest = readBoolean(),
            tombstoned = readBoolean(),
        )
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available())
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
