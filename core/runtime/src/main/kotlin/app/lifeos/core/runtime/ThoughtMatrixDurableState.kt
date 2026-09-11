package app.lifeos.core.runtime

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtLifecycleStatus
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import app.lifeos.core.runtime.thought.ThoughtNodeId
import app.lifeos.core.runtime.thought.ThoughtProjectionConflict
import app.lifeos.core.runtime.thought.ThoughtProvenance
import app.lifeos.core.runtime.thought.ThoughtRelation
import app.lifeos.core.runtime.thought.ThoughtRelationSource
import app.lifeos.core.runtime.thought.ThoughtRelationType
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import app.lifeos.core.runtime.thought.ThoughtNode as V2ThoughtNode

data class ThoughtMatrixDurableState(
    val v2Snapshot: ThoughtMatrixSnapshot,
    val legacyState: MatrixState,
)

interface ThoughtMatrixStateRepository {
    suspend fun save(state: ThoughtMatrixDurableState)
    suspend fun load(): ThoughtMatrixDurableState?
}

/** Bounded deterministic binary form for exact process-kill ThoughtMatrix restoration. */
object ThoughtMatrixDurableStateCodec {
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_ENTRIES = 100_000
    private const val MAX_TAGS = 4096
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(state: ThoughtMatrixDurableState): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(VERSION)
            val snapshot = state.v2Snapshot
            out.writeInt(snapshot.schemaVersion)
            out.writeLong(snapshot.revision)
            writeInstant(out, snapshot.capturedAt)
            out.writeInt(snapshot.nodes.size)
            snapshot.nodes.forEach { writeV2Node(out, it) }
            out.writeInt(snapshot.relations.size)
            snapshot.relations.forEach { writeRelation(out, it) }
            out.writeInt(snapshot.conflicts.size)
            snapshot.conflicts.forEach { writeConflict(out, it) }

            val legacy = state.legacyState.nodes.values.sortedBy { it.photonId.value }
            out.writeInt(legacy.size)
            legacy.forEach { writeLegacyNode(out, it) }
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "ThoughtMatrix durable state payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): ThoughtMatrixDurableState {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid ThoughtMatrix durable state payload size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported ThoughtMatrix durable state version" }
            val schemaVersion = input.readInt()
            val revision = input.readLong()
            val capturedAt = readInstant(input)
            val nodes = List(readCount(input, MAX_ENTRIES, "thought nodes")) { readV2Node(input) }
            val relations = List(readCount(input, MAX_ENTRIES, "thought relations")) { readRelation(input) }
            val conflicts = List(readCount(input, MAX_ENTRIES, "thought conflicts")) { readConflict(input) }
            val snapshot = ThoughtMatrixSnapshot(
                schemaVersion = schemaVersion,
                revision = revision,
                nodes = nodes,
                relations = relations,
                conflicts = conflicts,
                capturedAt = capturedAt,
            )

            val legacyNodes = List(readCount(input, MAX_ENTRIES, "legacy thought nodes")) {
                readLegacyNode(input)
            }
            require(legacyNodes.map { it.photonId }.distinct().size == legacyNodes.size) {
                "Duplicate legacy ThoughtMatrix photon ids"
            }
            require(input.available() == 0) { "Trailing ThoughtMatrix durable state data" }
            val legacyMap = legacyNodes.associateBy { it.photonId }
            ThoughtMatrixDurableState(
                v2Snapshot = snapshot,
                legacyState = MatrixState(
                    nodes = legacyMap,
                    totalEnergy = legacyNodes.sumOf { it.energy },
                ),
            )
        }
    }

    private fun writeV2Node(out: DataOutputStream, node: V2ThoughtNode) {
        writeString(out, node.id.value)
        writeString(out, node.provenance.sourcePhotonId.value)
        out.writeLong(node.provenance.sourceRevision)
        writeString(out, node.provenance.sourceFingerprint)
        writeString(out, node.provenance.source)
        writeString(out, node.provenance.actor)
        writeInstant(out, node.provenance.createdAt)
        writeString(out, node.fieldDomainId.value)
        writeString(out, node.semanticKey)
        writeString(out, node.summary)
        out.writeDouble(node.semanticMass)
        out.writeDouble(node.energy)
        out.writeDouble(node.confidence)
        writeNullableInstant(out, node.validity.validFrom)
        writeNullableInstant(out, node.validity.validUntilExclusive)
        writeString(out, node.lifecycle.name)
        writeString(out, node.verification.name)
        writeTags(out, node.tags)
    }

    private fun readV2Node(input: DataInputStream): V2ThoughtNode = V2ThoughtNode(
        id = ThoughtNodeId(readString(input)),
        provenance = ThoughtProvenance(
            sourcePhotonId = PhotonId(readString(input)),
            sourceRevision = input.readLong(),
            sourceFingerprint = readString(input),
            source = readString(input),
            actor = readString(input),
            createdAt = readInstant(input),
        ),
        fieldDomainId = FieldDomainId(readString(input)),
        semanticKey = readString(input),
        summary = readString(input),
        semanticMass = input.readDouble(),
        energy = input.readDouble(),
        confidence = input.readDouble(),
        validity = TemporalValidity(
            validFrom = readNullableInstant(input),
            validUntilExclusive = readNullableInstant(input),
        ),
        lifecycle = enumValueOf(readString(input)),
        verification = enumValueOf(readString(input)),
        tags = readTags(input),
    )

    private fun writeRelation(out: DataOutputStream, relation: ThoughtRelation) {
        writeString(out, relation.id)
        writeString(out, relation.sourceNodeId.value)
        writeString(out, relation.sourcePhotonId.value)
        writeString(out, relation.targetPhotonId.value)
        writeString(out, relation.type.name)
        out.writeDouble(relation.weight)
        writeString(out, relation.source.sourcePhotonId.value)
        out.writeLong(relation.source.sourceRevision)
        writeString(out, relation.source.origin)
    }

    private fun readRelation(input: DataInputStream): ThoughtRelation = ThoughtRelation(
        id = readString(input),
        sourceNodeId = ThoughtNodeId(readString(input)),
        sourcePhotonId = PhotonId(readString(input)),
        targetPhotonId = PhotonId(readString(input)),
        type = enumValueOf<ThoughtRelationType>(readString(input)),
        weight = input.readDouble(),
        source = ThoughtRelationSource(
            sourcePhotonId = PhotonId(readString(input)),
            sourceRevision = input.readLong(),
            origin = readString(input),
        ),
    )

    private fun writeConflict(out: DataOutputStream, conflict: ThoughtProjectionConflict) {
        writeString(out, conflict.nodeId.value)
        writeString(out, conflict.photonId.value)
        out.writeLong(conflict.sourceRevision)
        out.writeInt(conflict.fingerprints.size)
        conflict.fingerprints.forEach { writeString(out, it) }
    }

    private fun readConflict(input: DataInputStream): ThoughtProjectionConflict {
        val nodeId = ThoughtNodeId(readString(input))
        val photonId = PhotonId(readString(input))
        val revision = input.readLong()
        val fingerprints = List(readCount(input, MAX_TAGS, "conflict fingerprints")) { readString(input) }
        return ThoughtProjectionConflict(nodeId, photonId, revision, fingerprints)
    }

    private fun writeLegacyNode(out: DataOutputStream, node: ThoughtNode) {
        writeString(out, node.photonId.value)
        writeString(out, node.summary)
        out.writeDouble(node.energy)
        out.writeDouble(node.confidence)
        writeTags(out, node.tags)
        out.writeLong(node.revision)
    }

    private fun readLegacyNode(input: DataInputStream): ThoughtNode = ThoughtNode(
        photonId = PhotonId(readString(input)),
        summary = readString(input),
        energy = input.readDouble(),
        confidence = input.readDouble(),
        tags = readTags(input),
        revision = input.readLong(),
    )

    private fun writeTags(out: DataOutputStream, tags: Set<String>) {
        val sorted = tags.sorted()
        require(sorted.size <= MAX_TAGS) { "Too many ThoughtMatrix tags" }
        out.writeInt(sorted.size)
        sorted.forEach { writeString(out, it) }
    }

    private fun readTags(input: DataInputStream): Set<String> =
        List(readCount(input, MAX_TAGS, "tags")) { readString(input) }.toSet()

    private fun writeInstant(out: DataOutputStream, instant: Instant) {
        out.writeLong(instant.epochSecond)
        out.writeInt(instant.nano)
    }

    private fun readInstant(input: DataInputStream): Instant =
        Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())

    private fun writeNullableInstant(out: DataOutputStream, instant: Instant?) {
        out.writeBoolean(instant != null)
        if (instant != null) writeInstant(out, instant)
    }

    private fun readNullableInstant(input: DataInputStream): Instant? =
        if (input.readBoolean()) readInstant(input) else null

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "ThoughtMatrix durable string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid ThoughtMatrix durable string length"
        }
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun readCount(input: DataInputStream, max: Int, label: String): Int =
        input.readInt().also { require(it in 0..max) { "Invalid $label count" } }
}
