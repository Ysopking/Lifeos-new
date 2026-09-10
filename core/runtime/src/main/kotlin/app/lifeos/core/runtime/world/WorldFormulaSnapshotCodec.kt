package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldEquationContribution
import app.lifeos.core.field.world.WorldFieldEdgeId
import app.lifeos.core.field.world.WorldFieldNodeId
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldSignalDimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Stable bounded binary codec for encrypted durable WorldFormula snapshots. */
object WorldFormulaSnapshotCodec {
    const val FORMAT_VERSION = 1
    const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x57465331 // WFS1
    private const val MAX_STRING_BYTES = 1 * 1024 * 1024
    private const val MAX_NODES = 16_384
    private const val MAX_DIMENSIONS_PER_NODE = 64
    private const val MAX_PROVENANCE_PER_VALUE = 4_096
    private const val MAX_ITERATIONS = 100
    private const val MAX_CONTRIBUTIONS_PER_ITERATION = 65_536
    private const val MAX_CONFLICTS = 16_384
    private const val MAX_ANOMALIES = 16_384
    private const val MAX_EDGE_IDS_PER_CONFLICT = 65_536
    private const val MAX_INPUT_SNAPSHOTS = 65_536

    fun encode(snapshot: WorldFormulaSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(FORMAT_VERSION)
            stream.writeString(snapshot.id)
            stream.writeString(snapshot.runId)
            stream.writeString(snapshot.requestId)
            stream.writeString(snapshot.equationVersion)
            stream.writeString(snapshot.equationFingerprint)
            stream.writeString(snapshot.graphFingerprint)
            stream.writeString(snapshot.configFingerprint)
            stream.writeString(snapshot.status.name)
            stream.writeState(snapshot.finalState)

            require(snapshot.iterations.size <= MAX_ITERATIONS)
            stream.writeInt(snapshot.iterations.size)
            snapshot.iterations.forEach { iteration ->
                stream.writeInt(iteration.index)
                stream.writeString(iteration.beforeStateFingerprint)
                stream.writeString(iteration.afterStateFingerprint)
                stream.writeDouble(iteration.maxDelta)
                stream.writeInt(iteration.stableRounds)
                require(iteration.contributions.size <= MAX_CONTRIBUTIONS_PER_ITERATION)
                stream.writeInt(iteration.contributions.size)
                iteration.contributions.sortedBy { it.edgeId.value }.forEach { contribution ->
                    stream.writeContribution(contribution)
                }
            }

            require(snapshot.conflicts.size <= MAX_CONFLICTS)
            stream.writeInt(snapshot.conflicts.size)
            snapshot.conflicts.sortedBy { it.key }.forEach { conflict ->
                stream.writeString(conflict.key)
                stream.writeString(conflict.targetNodeId.value)
                stream.writeString(conflict.dimension.name)
                stream.writeStringSet(conflict.positiveEdgeIds, MAX_EDGE_IDS_PER_CONFLICT)
                stream.writeStringSet(conflict.negativeEdgeIds, MAX_EDGE_IDS_PER_CONFLICT)
                stream.writeDouble(conflict.maxPositive)
                stream.writeDouble(conflict.maxNegativeMagnitude)
            }

            require(snapshot.anomalies.size <= MAX_ANOMALIES)
            stream.writeInt(snapshot.anomalies.size)
            snapshot.anomalies.sortedBy { it.key }.forEach { anomaly ->
                stream.writeString(anomaly.type.name)
                stream.writeString(anomaly.key)
                stream.writeString(anomaly.detail)
            }
            stream.writeStringSet(snapshot.inputSnapshotFingerprints, MAX_INPUT_SNAPSHOTS)
        }
        return output.toByteArray().also { encoded ->
            require(encoded.isNotEmpty()) { "World formula snapshot encoding must not be empty" }
            require(encoded.size <= MAX_ENCODED_BYTES) { "World formula snapshot encoding too large" }
        }
    }

    fun decode(encoded: ByteArray): WorldFormulaSnapshot {
        require(encoded.isNotEmpty()) { "World formula snapshot payload must not be empty" }
        require(encoded.size <= MAX_ENCODED_BYTES) { "World formula snapshot payload too large" }
        val input = DataInputStream(ByteArrayInputStream(encoded))
        require(input.readInt() == MAGIC) { "Unsupported world formula snapshot magic" }
        require(input.readInt() == FORMAT_VERSION) { "Unsupported world formula snapshot format" }

        val id = input.readString()
        val runId = input.readString()
        val requestId = input.readString()
        val equationVersion = input.readString()
        val equationFingerprint = input.readString()
        val graphFingerprint = input.readString()
        val configFingerprint = input.readString()
        val status = enumValue<WorldFormulaStatus>(input.readString(), "world formula status")
        val state = input.readState()

        val iterationCount = input.readBoundedCount(MAX_ITERATIONS, "world formula iterations")
        val iterations = (0 until iterationCount).map {
            val index = input.readInt()
            val before = input.readString()
            val after = input.readString()
            val maxDelta = input.readDouble()
            val stableRounds = input.readInt()
            val contributionCount = input.readBoundedCount(
                MAX_CONTRIBUTIONS_PER_ITERATION,
                "world formula contributions",
            )
            WorldFormulaIteration(
                index = index,
                beforeStateFingerprint = before,
                afterStateFingerprint = after,
                maxDelta = maxDelta,
                stableRounds = stableRounds,
                contributions = (0 until contributionCount).map { input.readContribution() },
            )
        }

        val conflictCount = input.readBoundedCount(MAX_CONFLICTS, "world formula conflicts")
        val conflicts = (0 until conflictCount).map {
            WorldFormulaConflict(
                key = input.readString(),
                targetNodeId = WorldFieldNodeId(input.readString()),
                dimension = enumValue(input.readString(), "world conflict dimension"),
                positiveEdgeIds = input.readStringSet(MAX_EDGE_IDS_PER_CONFLICT),
                negativeEdgeIds = input.readStringSet(MAX_EDGE_IDS_PER_CONFLICT),
                maxPositive = input.readDouble(),
                maxNegativeMagnitude = input.readDouble(),
            )
        }

        val anomalyCount = input.readBoundedCount(MAX_ANOMALIES, "world formula anomalies")
        val anomalies = (0 until anomalyCount).map {
            WorldFormulaAnomaly(
                type = enumValue(input.readString(), "world anomaly type"),
                key = input.readString(),
                detail = input.readString(),
            )
        }
        val inputFingerprints = input.readStringSet(MAX_INPUT_SNAPSHOTS)
        require(input.available() == 0) { "Trailing world formula snapshot bytes are not allowed" }

        return WorldFormulaSnapshot(
            id = id,
            runId = runId,
            requestId = requestId,
            equationVersion = equationVersion,
            equationFingerprint = equationFingerprint,
            graphFingerprint = graphFingerprint,
            configFingerprint = configFingerprint,
            status = status,
            finalState = state,
            iterations = iterations,
            conflicts = conflicts,
            anomalies = anomalies,
            inputSnapshotFingerprints = inputFingerprints,
        )
    }

    private fun DataOutputStream.writeState(state: WorldFieldState) {
        writeString(state.graphFingerprint)
        writeString(state.equationFingerprint)
        writeInt(state.generation)
        val vectors = state.stableVectors()
        require(vectors.size <= MAX_NODES)
        writeInt(vectors.size)
        vectors.forEach { (nodeId, vector) ->
            writeString(nodeId.value)
            val values = vector.stableValues()
            require(values.size <= MAX_DIMENSIONS_PER_NODE)
            writeInt(values.size)
            values.forEach { value ->
                writeString(value.dimension.name)
                writeDouble(value.value)
                writeDouble(value.confidence)
                writeStringSet(value.provenanceFingerprints, MAX_PROVENANCE_PER_VALUE)
            }
        }
    }

    private fun DataInputStream.readState(): WorldFieldState {
        val graphFingerprint = readString()
        val equationFingerprint = readString()
        val generation = readInt()
        val nodeCount = readBoundedCount(MAX_NODES, "world state nodes")
        val vectors = linkedMapOf<WorldFieldNodeId, WorldFieldVector>()
        repeat(nodeCount) {
            val nodeId = WorldFieldNodeId(readString())
            require(nodeId !in vectors) { "Duplicate world state node id" }
            val valueCount = readBoundedCount(MAX_DIMENSIONS_PER_NODE, "world state dimensions")
            val values = (0 until valueCount).map {
                WorldDimensionValue(
                    dimension = enumValue(readString(), "world signal dimension"),
                    value = readDouble(),
                    confidence = readDouble(),
                    provenanceFingerprints = readStringSet(MAX_PROVENANCE_PER_VALUE),
                )
            }
            vectors[nodeId] = WorldFieldVector(values)
        }
        return WorldFieldState(
            graphFingerprint = graphFingerprint,
            equationFingerprint = equationFingerprint,
            generation = generation,
            vectors = vectors.toSortedMap(compareBy<WorldFieldNodeId> { it.value }),
        )
    }

    private fun DataOutputStream.writeContribution(value: WorldEquationContribution) {
        writeString(value.edgeId.value)
        writeString(value.sourceNodeId.value)
        writeString(value.targetNodeId.value)
        writeString(value.sourceDimension.name)
        writeString(value.targetDimension.name)
        writeString(value.coefficientId.value)
        writeDouble(value.signedDelta)
        writeDouble(value.confidence)
        writeString(value.provenanceFingerprint)
    }

    private fun DataInputStream.readContribution(): WorldEquationContribution = WorldEquationContribution(
        edgeId = WorldFieldEdgeId(readString()),
        sourceNodeId = WorldFieldNodeId(readString()),
        targetNodeId = WorldFieldNodeId(readString()),
        sourceDimension = enumValue(readString(), "world contribution source dimension"),
        targetDimension = enumValue(readString(), "world contribution target dimension"),
        coefficientId = WorldCoefficientId(readString()),
        signedDelta = readDouble(),
        confidence = readDouble(),
        provenanceFingerprint = readString(),
    )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "World formula snapshot string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readBoundedCount(MAX_STRING_BYTES, "world formula snapshot string")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeStringSet(values: Set<String>, max: Int) {
        require(values.size <= max)
        writeInt(values.size)
        values.sorted().forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringSet(max: Int): Set<String> {
        val count = readBoundedCount(max, "world formula string set")
        val values = linkedSetOf<String>()
        repeat(count) {
            require(values.add(readString())) { "Duplicate world formula set value" }
        }
        return values.toSortedSet()
    }

    private fun DataInputStream.readBoundedCount(max: Int, label: String): Int {
        val count = readInt()
        require(count in 0..max) { "Invalid $label count" }
        return count
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T = try {
        enumValueOf<T>(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid $label", error)
    }
}
