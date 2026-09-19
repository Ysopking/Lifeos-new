package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackEvidenceCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_OBSERVATIONS = 4096
    private const val MAX_ACTIVE_COEFFICIENTS = 4096

    fun encode(record: WorldEquationPackEvidenceRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(record.id)
            data.writeLong(record.revision)
            data.writeString(record.state.name)
            writeEvidence(data, record.evidence)
            data.writeNullableString(record.latestAssessmentId)
            data.writeString(record.fingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES) {
                "Structural pack evidence payload size is invalid"
            }
        }
    }

    fun decode(bytes: ByteArray): WorldEquationPackEvidenceRecord {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "Structural pack evidence payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported structural pack evidence codec"
            }
            val storedId = data.readString()
            val revision = data.readLong()
            val state = WorldEquationPackLifecycleState.valueOf(data.readString())
            val evidence = readEvidence(data)
            val latestAssessmentId = data.readNullableString()
            val storedFingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing structural pack evidence bytes"
            }
            WorldEquationPackEvidenceRecord.create(
                revision = revision,
                state = state,
                evidence = evidence,
                latestAssessmentId = latestAssessmentId,
            ).also {
                require(it.id == storedId) {
                    "Structural pack evidence id does not match content"
                }
                require(it.fingerprint == storedFingerprint) {
                    "Structural pack evidence fingerprint does not match content"
                }
            }
        }
    }

    private fun writeEvidence(
        data: DataOutputStream,
        evidence: WorldEquationPackEvidenceSet,
    ) {
        data.writeString(evidence.candidatePackVersion)
        data.writeString(evidence.candidatePackFingerprint)
        data.writeString(evidence.candidateStructuralFingerprint)
        data.writeString(evidence.baselinePackVersion)
        data.writeString(evidence.baselinePackFingerprint)
        data.writeString(evidence.baselineStructuralFingerprint)
        data.writeString(evidence.structuralPreflightId)
        data.writeString(evidence.registrySnapshotId)
        data.writeString(evidence.registryFingerprint)
        data.writeString(evidence.protocol.version)
        data.writeInt(evidence.protocol.minimumIndependentRuns)
        data.writeInt(evidence.protocol.minimumDistinctWorkloads)
        data.writeInt(evidence.protocol.minimumStructuralExerciseRuns)
        data.writeInt(evidence.protocol.minimumShadowRuns)
        data.writeInt(evidence.protocol.minimumHoldoutRuns)
        require(evidence.observations.size <= MAX_OBSERVATIONS)
        data.writeInt(evidence.observations.size)
        evidence.observations
            .sortedWith(compareBy({ it.runId }, { it.workloadId }, { it.fingerprint() }))
            .forEach { observation ->
                data.writeString(observation.caseFingerprint)
                data.writeString(observation.runId)
                data.writeString(observation.workloadId)
                data.writeString(observation.partition.name)
                data.writeString(observation.baselinePackFingerprint)
                data.writeString(observation.candidatePackFingerprint)
                writeMetrics(data, observation.baseline)
                writeMetrics(data, observation.candidate)
            }
    }

    private fun readEvidence(
        data: DataInputStream,
    ): WorldEquationPackEvidenceSet {
        val candidatePackVersion = data.readString()
        val candidatePackFingerprint = data.readString()
        val candidateStructuralFingerprint = data.readString()
        val baselinePackVersion = data.readString()
        val baselinePackFingerprint = data.readString()
        val baselineStructuralFingerprint = data.readString()
        val structuralPreflightId = data.readString()
        val registrySnapshotId = data.readString()
        val registryFingerprint = data.readString()
        val protocol = WorldEquationPackEvaluationProtocol(
            version = data.readString(),
            minimumIndependentRuns = data.readInt(),
            minimumDistinctWorkloads = data.readInt(),
            minimumStructuralExerciseRuns = data.readInt(),
            minimumShadowRuns = data.readInt(),
            minimumHoldoutRuns = data.readInt(),
        )
        val count = data.readInt()
        require(count in 0..MAX_OBSERVATIONS) {
            "Invalid structural pack evidence observation count"
        }
        val observations = buildList(count) {
            repeat(count) {
                add(
                    WorldEquationPackShadowObservation(
                        caseFingerprint = data.readString(),
                        runId = data.readString(),
                        workloadId = data.readString(),
                        partition = WorldEquationPackEvidencePartition.valueOf(data.readString()),
                        baselinePackFingerprint = data.readString(),
                        candidatePackFingerprint = data.readString(),
                        baseline = readMetrics(data),
                        candidate = readMetrics(data),
                    )
                )
            }
        }
        return WorldEquationPackEvidenceSet(
            candidatePackVersion = candidatePackVersion,
            candidatePackFingerprint = candidatePackFingerprint,
            candidateStructuralFingerprint = candidateStructuralFingerprint,
            baselinePackVersion = baselinePackVersion,
            baselinePackFingerprint = baselinePackFingerprint,
            baselineStructuralFingerprint = baselineStructuralFingerprint,
            structuralPreflightId = structuralPreflightId,
            registrySnapshotId = registrySnapshotId,
            registryFingerprint = registryFingerprint,
            protocol = protocol,
            observations = observations,
        )
    }

    private fun writeMetrics(
        data: DataOutputStream,
        metrics: WorldEquationPackRunMetrics,
    ) {
        data.writeString(metrics.status.name)
        data.writeInt(metrics.iterationCount)
        data.writeInt(metrics.conflictCount)
        data.writeInt(metrics.anomalyCount)
        data.writeDouble(metrics.terminalDelta)
        data.writeString(metrics.materializationFingerprint)
        data.writeNullableString(metrics.graphFingerprint)
        data.writeNullableString(metrics.finalStateFingerprint)
        require(metrics.activeCoefficientIds.size <= MAX_ACTIVE_COEFFICIENTS)
        data.writeInt(metrics.activeCoefficientIds.size)
        metrics.activeCoefficientIds.map { it.value }.sorted().forEach { data.writeString(it) }
    }

    private fun readMetrics(
        data: DataInputStream,
    ): WorldEquationPackRunMetrics {
        val status = WorldFormulaStatus.valueOf(data.readString())
        val iterationCount = data.readInt()
        val conflictCount = data.readInt()
        val anomalyCount = data.readInt()
        val terminalDelta = data.readDouble()
        val materializationFingerprint = data.readString()
        val graphFingerprint = data.readNullableString()
        val finalStateFingerprint = data.readNullableString()
        val activeCount = data.readInt()
        require(activeCount in 0..MAX_ACTIVE_COEFFICIENTS) {
            "Invalid structural pack active coefficient count"
        }
        val active = buildSet {
            repeat(activeCount) {
                add(WorldCoefficientId(data.readString()))
            }
        }
        return WorldEquationPackRunMetrics(
            status = status,
            iterationCount = iterationCount,
            conflictCount = conflictCount,
            anomalyCount = anomalyCount,
            terminalDelta = terminalDelta,
            activeCoefficientIds = active,
            materializationFingerprint = materializationFingerprint,
            graphFingerprint = graphFingerprint,
            finalStateFingerprint = finalStateFingerprint,
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "Structural pack evidence string too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) {
            "Invalid structural pack evidence string length"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null
}
