package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.PhotonCodec
import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Bounded deterministic codec for the encrypted pending-review vault. */
object OwnerAssetReviewCodec {
    const val VERSION = 1
    private const val MAGIC = 0x4C415231 // LAR1
    private const val MAX_RECORDS = 4_096
    const val MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024
    private const val MAX_STRING_BYTES = 512 * 1024
    private const val MAX_PHOTON_BYTES = 4 * 1024 * 1024

    fun encode(records: List<OwnerAssetReviewRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "Too many owner asset review records" }
        require(records.map { it.candidate.id }.distinct().size == records.size) {
            "Duplicate owner asset review candidate ids"
        }
        val canonical = records.sortedBy { it.candidate.id.value }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(canonical.size)
            canonical.forEach { out.writeRecord(it) }
        }
        require(bytes.size() <= MAX_PLAINTEXT_BYTES) { "Owner asset review vault payload too large" }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<OwnerAssetReviewRecord> {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Owner asset review vault payload too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid owner asset review codec magic" }
            require(input.readInt() == VERSION) { "Unsupported owner asset review codec version" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid owner asset review record count" }
            val records = buildList(count) { repeat(count) { add(input.readRecord()) } }
            require(input.available() == 0) { "Trailing owner asset review data" }
            require(records.map { it.candidate.id }.distinct().size == records.size) {
                "Duplicate owner asset review candidate ids"
            }
            records.sortedBy { it.candidate.id.value }
        }
    }

    private fun DataOutputStream.writeRecord(record: OwnerAssetReviewRecord) {
        val candidate = record.candidate
        text(candidate.id.value)
        text(candidate.subjectType.name)
        text(candidate.subjectId)
        text(candidate.revisionKey)
        text(candidate.kind.name)
        text(candidate.title)
        text(candidate.targetMimeType)
        instant(candidate.createdAt)

        count(candidate.participatingModules.size, OwnerAssetReviewCandidate.MAX_MODULES)
        candidate.participatingModules.sorted().forEach(::text)
        count(candidate.inputPhotonIds.size, OwnerAssetReviewCandidate.MAX_INPUT_PHOTONS)
        candidate.inputPhotonIds.sortedBy { it.value }.forEach { text(it.value) }

        writeBoolean(candidate.materializedAsset != null)
        candidate.materializedAsset?.let { asset ->
            text(asset.id.value)
            text(asset.mediaType)
            writeLong(asset.byteCount)
            text(asset.sha256)
        }

        count(candidate.stagedPhotons.size, OwnerAssetReviewCandidate.MAX_STAGED_PHOTONS)
        candidate.stagedPhotons.forEach { photon ->
            val encoded = PhotonCodec.encode(photon)
            require(encoded.size <= MAX_PHOTON_BYTES)
            writeInt(encoded.size)
            write(encoded)
        }

        nullableText(candidate.previewText)
        count(candidate.metadata.size, OwnerAssetReviewCandidate.MAX_METADATA_ENTRIES)
        candidate.metadata.toSortedMap().forEach { (key, value) ->
            text(key)
            text(value)
        }

        writeBoolean(record.decision != null)
        record.decision?.let { decision ->
            text(decision.candidateId.value)
            text(decision.decision.name)
            text(decision.ownerActorId)
            nullableText(decision.feedback)
            instant(decision.decidedAt)
            text(decision.decisionPhotonId.value)
        }
        writeBoolean(record.publishedAt != null)
        record.publishedAt?.let(::instant)
    }

    private fun DataInputStream.readRecord(): OwnerAssetReviewRecord {
        val encodedId = OwnerAssetReviewCandidateId(text())
        val subjectType = OwnerAssetReviewSubjectType.valueOf(text())
        val subjectId = text()
        val revisionKey = text()
        val kind = ArtifactKind.valueOf(text())
        val title = text()
        val mime = text()
        val createdAt = instant()

        val modules = buildSet {
            repeat(count(OwnerAssetReviewCandidate.MAX_MODULES)) { add(text()) }
        }
        val inputs = buildSet {
            repeat(count(OwnerAssetReviewCandidate.MAX_INPUT_PHOTONS)) { add(PhotonId(text())) }
        }

        val asset = if (readBoolean()) {
            AssetRef(
                id = AssetId(text()),
                mediaType = text(),
                byteCount = readLong(),
                sha256 = text(),
            )
        } else null

        val staged = buildList {
            repeat(count(OwnerAssetReviewCandidate.MAX_STAGED_PHOTONS)) {
                val size = readInt()
                require(size in 1..MAX_PHOTON_BYTES && size <= available()) {
                    "Invalid staged Photon payload size"
                }
                add(PhotonCodec.decode(ByteArray(size).also(::readFully)))
            }
        }
        val preview = nullableText()
        val metadata = buildMap {
            repeat(count(OwnerAssetReviewCandidate.MAX_METADATA_ENTRIES)) {
                val key = text()
                require(key !in this) { "Duplicate owner asset review metadata key" }
                put(key, text())
            }
        }

        val candidate = OwnerAssetReviewCandidate.create(
            subjectType = subjectType,
            subjectId = subjectId,
            revisionKey = revisionKey,
            kind = kind,
            title = title,
            targetMimeType = mime,
            createdAt = createdAt,
            participatingModules = modules,
            inputPhotonIds = inputs,
            materializedAsset = asset,
            stagedPhotons = staged,
            previewText = preview,
            metadata = metadata,
        )
        require(candidate.id == encodedId) { "Owner asset review candidate identity mismatch" }

        val decision = if (readBoolean()) {
            OwnerAssetReviewDecisionRecord(
                candidateId = OwnerAssetReviewCandidateId(text()),
                decision = OwnerAssetReviewDecision.valueOf(text()),
                ownerActorId = text(),
                feedback = nullableText(),
                decidedAt = instant(),
                decisionPhotonId = PhotonId(text()),
            )
        } else null
        val publishedAt = if (readBoolean()) instant() else null
        return OwnerAssetReviewRecord(candidate, decision, publishedAt)
    }

    private fun DataOutputStream.instant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.instant(): Instant {
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999)
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    private fun DataOutputStream.nullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) text(value)
    }

    private fun DataInputStream.nullableText(): String? = if (readBoolean()) text() else null

    private fun DataOutputStream.count(value: Int, maximum: Int) {
        require(value in 0..maximum)
        writeInt(value)
    }

    private fun DataInputStream.count(maximum: Int): Int = readInt().also {
        require(it in 0..maximum) { "Invalid owner asset review item count" }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Owner asset review string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES && size <= available()) {
            "Invalid owner asset review text length"
        }
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
