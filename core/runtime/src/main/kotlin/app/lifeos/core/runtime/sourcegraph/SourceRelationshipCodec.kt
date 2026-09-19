package app.lifeos.core.runtime.sourcegraph

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

object SourceRelationshipCodec {
    private const val MAGIC = 0x53524731
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val MAX_EVIDENCE = 4_096
    private const val MAX_BLOCKERS = 1_024
    private const val ENVELOPE_PREFIX = "source-relationship/v1:"

    fun encode(edge: SourceRelationshipEdge): String {
        val payload = ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeRef(edge.source)
                out.writeRef(edge.target)
                out.writeText(edge.type.name)
                out.writeText(edge.state.name)
                out.writeDouble(edge.confidence)
                out.writeEvidenceList(edge.positiveEvidence)
                out.writeEvidenceList(edge.negativeEvidence)
                out.writeInt(edge.blockers.size)
                edge.blockers.forEach { out.writeText(it) }
                out.writeText(edge.resolverId)
                out.writeText(edge.resolverVersion)
                out.writeInstant(edge.createdAt)
                out.writeInstant(edge.lastEvaluatedAt)
                out.writeText(edge.fingerprint)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_RECORD_BYTES) { "Source relationship payload exceeds bound" }
        return ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(content: String): SourceRelationshipEdge {
        require(content.startsWith(ENVELOPE_PREFIX)) { "Source relationship envelope is missing" }
        val payload = try {
            Base64.getUrlDecoder().decode(content.removePrefix(ENVELOPE_PREFIX))
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid source relationship envelope", error)
        }
        require(payload.size <= MAX_RECORD_BYTES) { "Source relationship payload exceeds bound" }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid source relationship magic" }
            require(input.readInt() == VERSION) { "Unsupported source relationship codec version" }
            val source = input.readRef()
            val target = input.readRef()
            val type = SourceRelationshipType.valueOf(input.readText())
            val state = SourceRelationshipState.valueOf(input.readText())
            val confidence = input.readDouble()
            val positive = input.readEvidenceList()
            val negative = input.readEvidenceList()
            val blockerCount = input.readInt().also {
                require(it in 0..MAX_BLOCKERS) { "Invalid source relationship blocker count" }
            }
            val blockers = List(blockerCount) { input.readText() }
            val resolverId = input.readText()
            val resolverVersion = input.readText()
            val createdAt = input.readInstant()
            val lastEvaluatedAt = input.readInstant()
            val expectedFingerprint = input.readText()
            require(input.available() == 0) { "Trailing source relationship bytes" }

            SourceRelationshipEdge(
                source = source,
                target = target,
                type = type,
                state = state,
                confidence = confidence,
                positiveEvidence = positive,
                negativeEvidence = negative,
                blockers = blockers,
                resolverId = resolverId,
                resolverVersion = resolverVersion,
                createdAt = createdAt,
                lastEvaluatedAt = lastEvaluatedAt,
            ).also { edge ->
                require(edge.fingerprint == expectedFingerprint) {
                    "Source relationship fingerprint mismatch"
                }
            }
        }
    }

    private fun DataOutputStream.writeEvidenceList(
        evidence: List<SourceRelationshipEvidence>,
    ) {
        require(evidence.size <= MAX_EVIDENCE)
        writeInt(evidence.size)
        evidence.sortedBy { it.evidenceId }.forEach { item ->
            writeText(item.evidenceId)
            writeText(item.family.name)
            writeText(item.kind.name)
            writeText(item.strength.name)
            writeText(item.polarity.name)
            writeRef(item.sourceRef)
            writeRef(item.lineageRoot)
            writeDouble(item.confidence)
            writeText(item.explanation)
        }
    }

    private fun DataInputStream.readEvidenceList(): List<SourceRelationshipEvidence> {
        val count = readInt().also {
            require(it in 0..MAX_EVIDENCE) { "Invalid source relationship evidence count" }
        }
        return List(count) {
            SourceRelationshipEvidence(
                evidenceId = readText(),
                family = RelationshipEvidenceFamily.valueOf(readText()),
                kind = RelationshipEvidenceKind.valueOf(readText()),
                strength = EvidenceStrength.valueOf(readText()),
                polarity = EvidencePolarity.valueOf(readText()),
                sourceRef = readRef(),
                lineageRoot = readRef(),
                confidence = readDouble(),
                explanation = readText(),
            )
        }.sortedBy { it.evidenceId }
    }

    private fun DataOutputStream.writeRef(ref: PhotonRevisionRef) {
        writeText(ref.photonId.value)
        writeLong(ref.revision)
    }

    private fun DataInputStream.readRef(): PhotonRevisionRef =
        PhotonRevisionRef(
            photonId = PhotonId(readText()),
            revision = readLong(),
        )

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(
            readLong(),
            readInt().also { require(it in 0..999_999_999) }.toLong(),
        )

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Source relationship text field exceeds bound" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available()) {
            "Invalid source relationship text size"
        }
        val bytes = ByteArray(size).also { readFully(it) }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
