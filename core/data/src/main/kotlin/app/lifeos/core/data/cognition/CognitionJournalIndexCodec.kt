package app.lifeos.core.data.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.cognition.CognitionJournalIndexEntry
import app.lifeos.core.runtime.cognition.CognitionJournalIndexSnapshot
import app.lifeos.core.runtime.cognition.CognitionJournalKind
import app.lifeos.core.runtime.cognition.CognitionJournalReservation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object CognitionJournalIndexCodec {
    const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val MAX_PENDING = 4

    fun encode(snapshot: CognitionJournalIndexSnapshot): ByteArray {
        val value = snapshot.canonical()
        val bytes = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(value.formatVersion)
                stream.writeInt(value.entries.size)
                value.entries.forEach { entry ->
                    stream.writeInt(entry.kind.ordinal)
                    stream.writeLong(entry.sequence)
                    stream.writeText(entry.stableId)
                    stream.writeText(entry.photonRef.photonId.value)
                    stream.writeLong(entry.photonRef.revision)
                    stream.writeLong(entry.recordedAt.epochSecond)
                    stream.writeInt(entry.recordedAt.nano)
                }

                stream.writeInt(value.pendingReservations.size)
                value.pendingReservations.forEach { reservation ->
                    stream.writeInt(reservation.kind.ordinal)
                    stream.writeLong(reservation.sequence)
                    stream.writeText(reservation.stableId)
                }
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Invalid cognition journal index payload size"
        }
        return bytes
    }

    fun decode(bytes: ByteArray): CognitionJournalIndexSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Invalid cognition journal index payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version == CognitionJournalIndexSnapshot.FORMAT_VERSION) {
            "Unsupported cognition journal index version"
        }

        val entryCount = input.readInt()
        require(entryCount in 0..CognitionJournalIndexSnapshot.MAX_ENTRIES) {
            "Invalid cognition journal index entry count"
        }
        val entries = ArrayList<CognitionJournalIndexEntry>(entryCount)
        repeat(entryCount) {
            val kind = input.readKind()
            val sequence = input.readLong()
            val stableId = input.readText()
            val photonId = PhotonId(input.readText())
            val revision = input.readLong()
            val epochSecond = input.readLong()
            val nano = input.readInt()
            require(nano in 0..999_999_999) { "Invalid cognition journal timestamp nanos" }
            entries += CognitionJournalIndexEntry(
                kind = kind,
                sequence = sequence,
                stableId = stableId,
                photonRef = PhotonRevisionRef(photonId, revision),
                recordedAt = Instant.ofEpochSecond(epochSecond, nano.toLong()),
            )
        }

        val pendingCount = input.readInt()
        require(pendingCount in 0..MAX_PENDING) {
            "Invalid cognition journal pending reservation count"
        }
        val pending = ArrayList<CognitionJournalReservation>(pendingCount)
        repeat(pendingCount) {
            pending += CognitionJournalReservation(
                kind = input.readKind(),
                sequence = input.readLong(),
                stableId = input.readText(),
            )
        }
        require(input.available() == 0) { "Trailing cognition journal index bytes" }
        return CognitionJournalIndexSnapshot(
            formatVersion = version,
            entries = entries,
            pendingReservations = pending,
        ).canonical()
    }

    private fun DataOutputStream.writeText(value: String) {
        require(value.isNotBlank()) { "Cognition journal index text must not be blank" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Cognition journal index text too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 1..MAX_TEXT_BYTES && size <= available()) {
            "Malformed cognition journal index text length"
        }
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.readKind(): CognitionJournalKind {
        val ordinal = readInt()
        val values = CognitionJournalKind.values()
        require(ordinal in values.indices) { "Invalid cognition journal kind" }
        return values[ordinal]
    }
}

internal object CognitionJournalIndexVaultCodec {
    private const val VERSION = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val MAX_CONTAINER_BYTES = CognitionJournalIndexCodec.MAX_PLAINTEXT_BYTES + 64 * 1024

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= CognitionJournalIndexCodec.MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(VERSION)
                stream.writeInt(cipher.iv.size)
                stream.write(cipher.iv)
                stream.writeInt(ciphertext.size)
                stream.write(ciphertext)
            }
            output.toByteArray()
        }.also {
            require(it.size <= MAX_CONTAINER_BYTES) {
                "Encrypted cognition journal index too large"
            }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
            "Invalid cognition journal index container size"
        }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == VERSION) { "Unsupported cognition journal vault version" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid cognition journal IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(
            ciphertextLength in 1..MAX_CONTAINER_BYTES &&
                ciphertextLength == input.available()
        ) {
            "Malformed cognition journal ciphertext length"
        }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext).also {
            require(
                it.isNotEmpty() &&
                    it.size <= CognitionJournalIndexCodec.MAX_PLAINTEXT_BYTES
            ) {
                "Invalid decrypted cognition journal index size"
            }
        }
    }
}
