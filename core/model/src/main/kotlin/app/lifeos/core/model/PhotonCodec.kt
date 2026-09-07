package app.lifeos.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Version 1 is read-only; version 2 preserves the complete photon graph metadata. */
object PhotonCodec {
    const val VERSION = 2
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_ITEMS = 10_000

    fun encode(value: Photon): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.text(value.id.value); out.writeLong(value.revision); out.text(value.content)
            out.text(value.mimeType); out.text(value.phase.name); out.writeDouble(value.semanticMass)
            out.writeDouble(value.energy); out.writeDouble(value.confidence)
            out.text(value.provenance.source); out.text(value.provenance.actor)
            out.writeLong(value.provenance.createdAt.epochSecond); out.writeInt(value.provenance.createdAt.nano)
            out.count(value.tags.size); value.tags.sorted().forEach { out.text(it) }
            out.count(value.provenance.parentIds.size)
            value.provenance.parentIds.sortedBy { it.value }.forEach { out.text(it.value) }
            out.count(value.relations.size)
            value.relations.sortedWith(compareBy({ it.target.value }, { it.type.name }, { it.weight })).forEach {
                out.text(it.target.value); out.text(it.type.name); out.writeDouble(it.weight)
            }
        }
        require(bytes.size() <= MAX_BYTES) { "Photon exceeds size limit" }
    }.toByteArray()

    fun decode(bytes: ByteArray, version: Int = VERSION): Photon {
        require(version in 1..VERSION) { "Unsupported photon format" }
        require(bytes.size <= MAX_BYTES) { "Photon exceeds size limit" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun text() = if (version == 1) input.readUTF() else input.text()
            val id = PhotonId(text()); val revision = input.readLong(); val content = text()
            val mime = text(); val phase = PhotonPhase.valueOf(text()); val mass = input.readDouble()
            val energy = input.readDouble(); val confidence = input.readDouble()
            val source = text(); val actor = text()
            val created = if (version == 1) Instant.ofEpochMilli(input.readLong()) else {
                val seconds = input.readLong(); val nanos = input.readInt()
                require(nanos in 0..999_999_999)
                Instant.ofEpochSecond(seconds, nanos.toLong())
            }
            val tags = buildSet { repeat(input.count()) { add(text()) } }
            val parents = buildSet { if (version >= 2) repeat(input.count()) { add(PhotonId(text())) } }
            val relations = buildSet {
                if (version >= 2) repeat(input.count()) {
                    add(PhotonRelation(PhotonId(text()), RelationType.valueOf(text()), input.readDouble()))
                }
            }
            require(input.available() == 0) { "Trailing photon data" }
            require(mass.isFinite() && energy.isFinite())
            Photon(id, revision, content, mime, phase, mass, energy, confidence,
                Provenance(source, actor, created, parents), relations, tags)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_BYTES && size <= available()) { "Invalid text length" }
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun DataOutputStream.count(size: Int) { require(size in 0..MAX_ITEMS); writeInt(size) }
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_ITEMS) }
}
