package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

private const val SHADOW_EVIDENCE_MIME = "application/vnd.lifeos.field-shadow-evidence-bucket.v1"
private const val SHADOW_EVIDENCE_ROOT_TAG = "field-shadow-validation-bucket"
private const val CUTOVER_STATE_MIME = "application/vnd.lifeos.field-cutover-state.v1"
private const val CUTOVER_STATE_ROOT_TAG = "field-cutover-state"

class PhotonBackedFieldShadowValidationLedger(
    private val photons: RevisionedPhotonRepository,
    private val capacityPerDomain: Int = 256,
) : FieldShadowValidationLedger {
    init {
        require(capacityPerDomain in 1..MAX_CAPACITY)
    }

    override suspend fun record(evidence: FieldShadowValidationEvidence): Boolean {
        repeat(MAX_CAS_RETRIES) {
            val id = bucketId(evidence.domainId)
            val existingPhoton = photons.load(id)
            val existing = existingPhoton?.let(::decodeEvidenceBucket).orEmpty()
            if (existing.any { it.id == evidence.id }) return false

            val nextValues = (existing + evidence)
                .sortedWith(compareBy<FieldShadowValidationEvidence> { it.capturedAt }.thenBy { it.id })
                .takeLast(capacityPerDomain)
            val revision = (existingPhoton?.revision ?: 0L) + 1L
            val next = evidenceBucketPhoton(
                id = id,
                revision = revision,
                domainId = evidence.domainId,
                evidence = nextValues,
                at = evidence.capturedAt,
            )
            when (
                photons.saveRevision(
                    photon = next,
                    expectedPreviousRevision = existingPhoton?.revision,
                )
            ) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent,
                -> return true
                is PhotonRevisionWriteResult.Conflict -> Unit
            }
        }
        error("Field shadow evidence CAS retries exhausted")
    }

    override suspend fun latest(limit: Int): List<FieldShadowValidationEvidence> {
        require(limit > 0)
        return loadAllBuckets()
            .flatten()
            .sortedWith(
                compareByDescending<FieldShadowValidationEvidence> { it.capturedAt }
                    .thenByDescending { it.id }
            )
            .take(limit)
    }

    override suspend fun forDomain(
        domainId: FieldDomainId,
    ): List<FieldShadowValidationEvidence> =
        photons.load(bucketId(domainId))
            ?.let(::decodeEvidenceBucket)
            .orEmpty()
            .sortedWith(compareBy<FieldShadowValidationEvidence> { it.capturedAt }.thenBy { it.id })

    private suspend fun loadAllBuckets(): List<List<FieldShadowValidationEvidence>> {
        val refs = mutableListOf<PhotonRevisionRef>()
        var cursor: PhotonIndexCursor? = null
        while (refs.size <= MAX_DOMAIN_BUCKETS) {
            val remaining = MAX_DOMAIN_BUCKETS + 1 - refs.size
            val limit = minOf(PhotonIndexQuery.HARD_PAGE_LIMIT, remaining)
            val page = photons.query(
                PhotonIndexQuery(
                    mimeTypes = setOf(SHADOW_EVIDENCE_MIME),
                    allTags = setOf(SHADOW_EVIDENCE_ROOT_TAG),
                    latestOnly = true,
                    order = PhotonIndexOrder.IDENTITY,
                    after = cursor,
                    limit = limit,
                )
            )
            refs += page
            if (page.size < limit) break
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, page.last())
        }
        require(refs.size <= MAX_DOMAIN_BUCKETS) {
            "Field shadow evidence domain bucket limit exceeded"
        }
        return refs.map { ref ->
            val photon = requireNotNull(photons.load(ref)) {
                "Field shadow evidence index references missing photon"
            }
            decodeEvidenceBucket(photon)
        }
    }

    private fun bucketId(domainId: FieldDomainId): PhotonId = PhotonId(
        "field-shadow-bucket:" + StableFieldIds.fingerprint(
            "field-shadow-bucket/v1",
            domainId.value,
        )
    )

    private fun evidenceBucketPhoton(
        id: PhotonId,
        revision: Long,
        domainId: FieldDomainId,
        evidence: List<FieldShadowValidationEvidence>,
        at: Instant,
    ): Photon = Photon(
        id = id,
        revision = revision,
        content = FieldShadowEvidenceBucketCodec.encode(domainId, evidence),
        mimeType = SHADOW_EVIDENCE_MIME,
        phase = PhotonPhase.CONVERGED,
        semanticMass = 1.0,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "field-shadow-validation",
            actor = "field-cutover-evidence-ledger",
            createdAt = at,
        ),
        tags = setOf(
            SHADOW_EVIDENCE_ROOT_TAG,
            "field-shadow-domain:" + StableFieldIds.fingerprint(domainId.value),
        ),
    )

    private fun decodeEvidenceBucket(photon: Photon): List<FieldShadowValidationEvidence> {
        require(photon.mimeType == SHADOW_EVIDENCE_MIME)
        val decoded = FieldShadowEvidenceBucketCodec.decode(photon.content)
        require(bucketId(decoded.first) == photon.id) {
            "Field shadow evidence bucket domain/id mismatch"
        }
        return decoded.second
    }

    private companion object {
        const val MAX_CAPACITY = 1024
        const val MAX_DOMAIN_BUCKETS = 256
        const val MAX_CAS_RETRIES = 32
    }
}

class PhotonBackedFieldCutoverStateRepository(
    private val photons: RevisionedPhotonRepository,
) : FieldCutoverStateRepository {
    override suspend fun load(domainId: FieldDomainId): FieldCutoverState? {
        val photon = photons.load(stateId(domainId)) ?: return null
        require(photon.mimeType == CUTOVER_STATE_MIME)
        val state = FieldCutoverStateCodec.decode(photon.content)
        require(state.domainId == domainId)
        require(state.revision == photon.revision) {
            "Field cutover state revision/photon revision mismatch"
        }
        return state
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        state: FieldCutoverState,
    ): Boolean {
        require(state.revision == (expectedRevision ?: 0L) + 1L) {
            "Field cutover state revision must advance exactly once"
        }
        val photon = Photon(
            id = stateId(state.domainId),
            revision = state.revision,
            content = FieldCutoverStateCodec.encode(state),
            mimeType = CUTOVER_STATE_MIME,
            phase = if (state.mode == FieldCutoverMode.AUTHORITATIVE) {
                PhotonPhase.ACTIVE
            } else {
                PhotonPhase.REFLECTING
            },
            semanticMass = 1.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "field-cutover-authority",
                actor = "field-cutover-state-repository",
                createdAt = state.updatedAt,
            ),
            tags = setOf(
                CUTOVER_STATE_ROOT_TAG,
                "field-cutover-domain:" + StableFieldIds.fingerprint(state.domainId.value),
                "field-cutover-mode:" + state.mode.name.lowercase(),
            ),
        )
        return when (
            photons.saveRevision(
                photon = photon,
                expectedPreviousRevision = expectedRevision,
            )
        ) {
            is PhotonRevisionWriteResult.Created,
            is PhotonRevisionWriteResult.Advanced,
            is PhotonRevisionWriteResult.Idempotent,
            -> true
            is PhotonRevisionWriteResult.Conflict -> false
        }
    }

    private fun stateId(domainId: FieldDomainId): PhotonId = PhotonId(
        "field-cutover-state:" + StableFieldIds.fingerprint(
            "field-cutover-state/v1",
            domainId.value,
        )
    )
}

private object FieldShadowEvidenceBucketCodec {
    private const val MAGIC = 0x46534542
    private const val VERSION = 1
    private const val MAX_EVIDENCE = 1024
    private const val MAX_STRING_BYTES = 64 * 1024
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun encode(
        domainId: FieldDomainId,
        evidence: List<FieldShadowValidationEvidence>,
    ): String {
        require(evidence.size <= MAX_EVIDENCE)
        require(evidence.all { it.domainId == domainId })
        val bytes = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                writeString(stream, domainId.value)
                stream.writeInt(evidence.size)
                evidence.forEach { writeEvidence(stream, it) }
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        return bytes.toHex()
    }

    fun decode(content: String): Pair<FieldDomainId, List<FieldShadowValidationEvidence>> {
        val bytes = content.hexToBytes(MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid field shadow evidence magic" }
        require(input.readInt() == VERSION) { "Unsupported field shadow evidence version" }
        val domainId = FieldDomainId(readString(input))
        val count = input.readInt()
        require(count in 0..MAX_EVIDENCE)
        val values = List(count) { readEvidence(input, domainId) }
        require(input.available() == 0) { "Trailing field shadow evidence bytes" }
        return domainId to values
    }

    private fun writeEvidence(
        output: DataOutputStream,
        evidence: FieldShadowValidationEvidence,
    ) {
        writeString(output, evidence.id)
        writeString(output, evidence.taskId.value)
        writeString(output, evidence.origin.name)
        writeNullableString(output, evidence.replayCaseId)

        writeString(output, evidence.legacy.finalState.name)
        writeString(output, evidence.legacy.semanticState.name)
        output.writeInt(evidence.legacy.influenceCount)
        writeStringSet(output, evidence.legacy.influenceTypes)
        output.writeDouble(evidence.legacy.averageConfidence)
        output.writeDouble(evidence.legacy.totalEnergyDelta)

        writeString(output, evidence.universal.shadowState.name)
        writeString(output, evidence.universal.semanticState.name)
        writeNullableString(output, evidence.universal.convergenceStatus?.name)
        output.writeBoolean(evidence.universal.snapshotPresent)
        output.writeInt(evidence.universal.winnerCount)
        output.writeDouble(evidence.universal.topConfidence)

        writeString(output, evidence.sourceStatus.name)
        writeString(output, evidence.taskOwnershipStatus.name)
        writeString(output, evidence.difference.name)
        output.writeDouble(evidence.confidenceDelta)
        writeString(output, evidence.capturedAt.toString())
    }

    private fun readEvidence(
        input: DataInputStream,
        domainId: FieldDomainId,
    ): FieldShadowValidationEvidence = FieldShadowValidationEvidence(
        id = readString(input),
        taskId = TaskId(readString(input)),
        domainId = domainId,
        origin = FieldShadowValidationOrigin.valueOf(readString(input)),
        replayCaseId = readNullableString(input),
        legacy = LegacyFieldObservation(
            finalState = TaskState.valueOf(readString(input)),
            semanticState = ShadowSemanticState.valueOf(readString(input)),
            influenceCount = input.readInt(),
            influenceTypes = readStringSet(input),
            averageConfidence = input.readDouble(),
            totalEnergyDelta = input.readDouble(),
        ),
        universal = UniversalFieldObservation(
            shadowState = FieldShadowState.valueOf(readString(input)),
            semanticState = ShadowSemanticState.valueOf(readString(input)),
            convergenceStatus = readNullableString(input)?.let(ConvergenceStatus::valueOf),
            snapshotPresent = input.readBoolean(),
            winnerCount = input.readInt(),
            topConfidence = input.readDouble(),
        ),
        sourceStatus = ShadowSourceStatus.valueOf(readString(input)),
        taskOwnershipStatus = ShadowTaskOwnershipStatus.valueOf(readString(input)),
        difference = FieldShadowDifferenceClass.valueOf(readString(input)),
        confidenceDelta = input.readDouble(),
        capturedAt = Instant.parse(readString(input)),
    )

    private fun writeStringSet(output: DataOutputStream, values: Set<String>) {
        val sorted = values.sorted()
        output.writeInt(sorted.size)
        sorted.forEach { writeString(output, it) }
    }

    private fun readStringSet(input: DataInputStream): Set<String> {
        val count = input.readInt()
        require(count in 0..MAX_EVIDENCE)
        return buildSet { repeat(count) { add(readString(input)) } }
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}

private object FieldCutoverStateCodec {
    private const val MAGIC = 0x46435354
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 256 * 1024

    fun encode(state: FieldCutoverState): String {
        val bytes = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                writeString(stream, state.domainId.value)
                stream.writeLong(state.generation)
                stream.writeLong(state.revision)
                writeString(stream, state.mode.name)
                writeNullableString(stream, state.evidenceFingerprint)
                stream.writeInt(state.replayCaseCount)
                writeString(stream, state.updatedAt.toString())
                writeNullableString(stream, state.authoritativeSince?.toString())
                writeString(stream, state.provenance)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_PAYLOAD_BYTES)
        return bytes.toHex()
    }

    fun decode(content: String): FieldCutoverState {
        val input = DataInputStream(
            ByteArrayInputStream(content.hexToBytes(MAX_PAYLOAD_BYTES))
        )
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val state = FieldCutoverState(
            domainId = FieldDomainId(readString(input)),
            generation = input.readLong(),
            revision = input.readLong(),
            mode = FieldCutoverMode.valueOf(readString(input)),
            evidenceFingerprint = readNullableString(input),
            replayCaseCount = input.readInt(),
            updatedAt = Instant.parse(readString(input)),
            authoritativeSince = readNullableString(input)?.let(Instant::parse),
            provenance = readString(input),
        )
        require(input.available() == 0)
        return state
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..64 * 1024 && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.hexToBytes(maxBytes: Int): ByteArray {
    require(length % 2 == 0)
    require(length / 2 in 1..maxBytes)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
