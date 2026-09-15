package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.EvidenceId
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Versioned, bounded binary representation of one immutable InformationAsset revision. */
object InformationAssetCodec {
    private const val MAGIC = 0x49415354 // IAST
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 512 * 1024
    private const val MAX_ITEMS = 16_384
    const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

    fun encode(revision: InformationAssetRevision): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            writeRequest(out, revision.request)
            writeManifest(out, revision.manifest)

            writeSize(out, revision.evidenceBindings.size)
            revision.evidenceBindings.sortedBy { it.id.value }.forEach { writeEvidence(out, it) }

            writeSize(out, revision.claims.size)
            revision.claims.sortedBy { it.id.value }.forEach { writeClaim(out, it) }

            writeSize(out, revision.conflicts.size)
            revision.conflicts.sortedBy { it.id.value }.forEach { writeConflict(out, it) }
        }
        return bytes.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Information asset payload exceeds bounded codec size"
            }
        }
    }

    fun decode(bytes: ByteArray): InformationAssetRevision {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) {
            "Information asset payload size is invalid"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid InformationAsset payload magic" }
        require(input.readInt() == VERSION) { "Unsupported InformationAsset payload version" }

        val request = readRequest(input)
        val manifest = readManifest(input)
        val evidence = List(readSize(input)) { readEvidence(input) }
        val claims = List(readSize(input)) { readClaim(input) }
        val conflicts = List(readSize(input)) { readConflict(input) }
        require(input.available() == 0) { "Trailing InformationAsset payload bytes" }

        return InformationAssetRevision(
            request = request,
            evidenceBindings = evidence.sortedBy { it.id.value },
            claims = claims.sortedBy { it.id.value },
            conflicts = conflicts.sortedBy { it.id.value },
            manifest = manifest,
        )
    }

    fun canonicalFingerprint(revision: InformationAssetRevision): String =
        InformationAssetFingerprints.fingerprint(
            "information-asset-codec/v1",
            encode(revision).toHex(),
        )

    private fun writeRequest(out: DataOutputStream, value: InformationAssetRequest) {
        write(out, value.id.value)
        write(out, value.kind.name)
        write(out, value.title)
        write(out, value.primaryDomainId.value)
        writeStringSet(out, value.requiredSemanticKeys)
    }

    private fun readRequest(input: DataInputStream): InformationAssetRequest = InformationAssetRequest(
        id = InformationAssetId(read(input)),
        kind = enumValueOf(read(input)),
        title = read(input),
        primaryDomainId = FieldDomainId(read(input)),
        requiredSemanticKeys = readStringSet(input),
    )

    private fun writeManifest(out: DataOutputStream, value: InformationAssetRevisionManifest) {
        write(out, value.id.value)
        out.writeBoolean(value.parent != null)
        value.parent?.let { parent ->
            write(out, parent.assetId.value)
            write(out, parent.revisionId.value)
            write(out, parent.photonId.value)
        }
        writeSize(out, value.sourcePhotons.size)
        value.sourcePhotons
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
            .forEach { source ->
                write(out, source.photonId.value)
                out.writeLong(source.revision)
                write(out, source.inputStateHash.value)
                write(out, source.semanticStateHash.value)
            }
        writeStringSet(out, value.domainIds.mapTo(linkedSetOf()) { it.value })
        writeStringSet(out, value.participatingModules)
        write(out, value.stateHash.value)
        write(out, value.resolution.name)
    }

    private fun readManifest(input: DataInputStream): InformationAssetRevisionManifest {
        val id = InformationAssetRevisionId(read(input))
        val parent = if (input.readBoolean()) {
            InformationAssetRevisionRef(
                assetId = InformationAssetId(read(input)),
                revisionId = InformationAssetRevisionId(read(input)),
                photonId = PhotonId(read(input)),
            )
        } else {
            null
        }
        val sources = List(readSize(input)) {
            PhotonRevisionReference(
                photonId = PhotonId(read(input)),
                revision = input.readLong(),
                inputStateHash = CognitiveStateHash(read(input)),
                semanticStateHash = CognitiveStateHash(read(input)),
            )
        }
        return InformationAssetRevisionManifest(
            id = id,
            parent = parent,
            sourcePhotons = sources,
            domainIds = readStringSet(input).mapTo(linkedSetOf(), ::FieldDomainId),
            participatingModules = readStringSet(input),
            stateHash = CognitiveStateHash(read(input)),
            resolution = enumValueOf(read(input)),
        )
    }

    private fun writeEvidence(out: DataOutputStream, value: InformationEvidenceBinding) {
        write(out, value.id.value)
        write(out, value.source.photonId.value)
        out.writeLong(value.source.revision)
        write(out, value.source.inputStateHash.value)
        write(out, value.source.semanticStateHash.value)
        writeNullable(out, value.fieldEvidenceId?.value)
        write(out, value.domainId.value)
        write(out, value.authority.name)
        out.writeDouble(value.confidence)
        out.writeDouble(value.reliability.score)
        write(out, value.reliability.reason)
        writeNullable(out, value.validity.validFrom?.toString())
        writeNullable(out, value.validity.validUntilExclusive?.toString())
        write(out, value.observedAt.toString())
        write(out, value.payloadFingerprint)
    }

    private fun readEvidence(input: DataInputStream): InformationEvidenceBinding {
        val storedId = InformationEvidenceBindingId(read(input))
        val source = PhotonRevisionReference(
            photonId = PhotonId(read(input)),
            revision = input.readLong(),
            inputStateHash = CognitiveStateHash(read(input)),
            semanticStateHash = CognitiveStateHash(read(input)),
        )
        val fieldEvidenceId = readNullable(input)?.let(::EvidenceId)
        val domainId = FieldDomainId(read(input))
        val authority = enumValueOf<SourceAuthority>(read(input))
        val confidence = input.readDouble()
        val reliability = EvidenceReliability(input.readDouble(), read(input))
        val validity = TemporalValidity(
            validFrom = readNullable(input)?.let(Instant::parse),
            validUntilExclusive = readNullable(input)?.let(Instant::parse),
        )
        val observedAt = Instant.parse(read(input))
        val payloadFingerprint = read(input)
        val value = InformationEvidenceBinding.create(
            source = source,
            fieldEvidenceId = fieldEvidenceId,
            domainId = domainId,
            authority = authority,
            confidence = confidence,
            reliability = reliability,
            validity = validity,
            observedAt = observedAt,
            payloadFingerprint = payloadFingerprint,
        )
        require(value.id == storedId) { "Information evidence binding fingerprint mismatch" }
        return value
    }

    private fun writeClaim(out: DataOutputStream, value: InformationClaim) {
        write(out, value.id.value)
        write(out, value.domainId.value)
        write(out, value.semanticKey)
        write(out, value.statement)
        write(out, value.state.name)
        out.writeDouble(value.confidence)
        writeStringSet(out, value.evidenceBindingIds.mapTo(linkedSetOf()) { it.value })
        writeStringSet(out, value.derivedFromClaimIds.mapTo(linkedSetOf()) { it.value })
        write(out, value.explanation)
    }

    private fun readClaim(input: DataInputStream): InformationClaim {
        val storedId = InformationClaimId(read(input))
        val domainId = FieldDomainId(read(input))
        val semanticKey = read(input)
        val statement = read(input)
        val state = enumValueOf<InformationClaimState>(read(input))
        val confidence = input.readDouble()
        val evidenceIds = readStringSet(input).mapTo(linkedSetOf(), ::InformationEvidenceBindingId)
        val parentClaimIds = readStringSet(input).mapTo(linkedSetOf(), ::InformationClaimId)
        val explanation = read(input)
        val value = InformationClaim.create(
            domainId = domainId,
            semanticKey = semanticKey,
            statement = statement,
            state = state,
            confidence = confidence,
            evidenceBindingIds = evidenceIds,
            derivedFromClaimIds = parentClaimIds,
            explanation = explanation,
        )
        require(value.id == storedId) { "Information claim fingerprint mismatch" }
        return value
    }

    private fun writeConflict(out: DataOutputStream, value: InformationConflict) {
        write(out, value.id.value)
        write(out, value.domainId.value)
        writeStringSet(out, value.claimIds.mapTo(linkedSetOf()) { it.value })
        out.writeDouble(value.severity)
        write(out, value.state.name)
        write(out, value.explanation)
    }

    private fun readConflict(input: DataInputStream): InformationConflict {
        val storedId = InformationConflictId(read(input))
        val domainId = FieldDomainId(read(input))
        val claimIds = readStringSet(input).mapTo(linkedSetOf(), ::InformationClaimId)
        val severity = input.readDouble()
        val state = enumValueOf<InformationConflictResolutionState>(read(input))
        val explanation = read(input)
        val value = InformationConflict.create(
            domainId = domainId,
            claimIds = claimIds,
            severity = severity,
            state = state,
            explanation = explanation,
        )
        require(value.id == storedId) { "Information conflict fingerprint mismatch" }
        return value
    }

    private fun writeStringSet(out: DataOutputStream, values: Set<String>) {
        writeSize(out, values.size)
        values.sorted().forEach { write(out, it) }
    }

    private fun readStringSet(input: DataInputStream): Set<String> {
        val count = readSize(input)
        val values = List(count) { read(input) }
        require(values.distinct().size == values.size) { "Duplicate InformationAsset set entry" }
        return values.toSortedSet()
    }

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) write(out, value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) read(input) else null

    private fun writeSize(out: DataOutputStream, size: Int) {
        require(size in 0..MAX_ITEMS) { "InformationAsset collection exceeds item bound" }
        out.writeInt(size)
    }

    private fun readSize(input: DataInputStream): Int = input.readInt().also {
        require(it in 0..MAX_ITEMS) { "InformationAsset collection size is invalid" }
    }

    private fun write(out: DataOutputStream, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_STRING_BYTES) { "InformationAsset string exceeds byte bound" }
        out.writeInt(data.size)
        out.write(data)
    }

    private fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
            "InformationAsset string length is invalid"
        }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
