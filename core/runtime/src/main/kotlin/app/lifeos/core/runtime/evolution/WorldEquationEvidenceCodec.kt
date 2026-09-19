package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationEvidenceCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_OBSERVATIONS = 10_000
    private const val MAX_ACTIVE_COEFFICIENTS = 4096

    fun encode(record: WorldEquationEvidenceRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(record.id)
            data.writeLong(record.revision)
            data.writeString(record.state.name)
            writeEvidence(data, record.evidence)
            data.writeNullableString(record.latestVerdictId)
            data.writeNullableString(record.activationHeadFingerprint)
            data.writeNullableString(record.rollbackDecisionId)
            data.writeString(record.fingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES) {
                "World equation evidence payload size is invalid"
            }
        }
    }

    fun decode(bytes: ByteArray): WorldEquationEvidenceRecord {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "World equation evidence payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported WorldEquation evidence codec"
            }
            val storedId = data.readString()
            val revision = data.readLong()
            val state = WorldEquationLifecycleState.valueOf(data.readString())
            val evidence = readEvidence(data)
            val verdictId = data.readNullableString()
            val activationHead = data.readNullableString()
            val rollbackDecision = data.readNullableString()
            val storedFingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing WorldEquation evidence bytes"
            }
            WorldEquationEvidenceRecord.create(
                revision = revision,
                state = state,
                evidence = evidence,
                latestVerdictId = verdictId,
                activationHeadFingerprint = activationHead,
                rollbackDecisionId = rollbackDecision,
            ).also {
                require(it.id == storedId) {
                    "WorldEquation evidence id does not match content"
                }
                require(it.fingerprint == storedFingerprint) {
                    "WorldEquation evidence fingerprint does not match content"
                }
            }
        }
    }

    private fun writeEvidence(
        data: DataOutputStream,
        evidence: WorldEquationEvidenceSet,
    ) {
        data.writeString(evidence.candidateVersion)
        data.writeString(evidence.candidateEquationFingerprint)
        data.writeString(evidence.candidatePhysicsFingerprint)
        data.writeString(evidence.baselineVersion)
        data.writeString(evidence.baselineEquationFingerprint)
        data.writeString(evidence.baselinePhysicsFingerprint)
        data.writeString(evidence.equationSchemaFingerprint)
        data.writeString(evidence.protocol.version)
        data.writeString(evidence.protocol.primaryMetric.name)
        data.writeInt(evidence.protocol.minimumIndependentRuns)
        data.writeInt(evidence.protocol.minimumDistinctWorkloads)
        data.writeInt(evidence.protocol.minimumActiveObservationsPerChangedCoefficient)
        data.writeString(evidence.policyFingerprint)
        require(evidence.observations.size <= MAX_OBSERVATIONS)
        data.writeInt(evidence.observations.size)
        evidence.observations
            .sortedWith(compareBy({ it.runId }, { it.workloadId }, { it.fingerprint() }))
            .forEach { observation ->
                data.writeString(observation.caseFingerprint)
                data.writeString(observation.runId)
                data.writeString(observation.workloadId)
                data.writeString(observation.baselineEquationFingerprint)
                data.writeString(observation.candidateEquationFingerprint)
                writeMetrics(data, observation.baseline)
                writeMetrics(data, observation.candidate)
            }
    }

    private fun readEvidence(data: DataInputStream): WorldEquationEvidenceSet {
        val candidateVersion = data.readString()
        val candidateEquationFingerprint = data.readString()
        val candidatePhysicsFingerprint = data.readString()
        val baselineVersion = data.readString()
        val baselineEquationFingerprint = data.readString()
        val baselinePhysicsFingerprint = data.readString()
        val schemaFingerprint = data.readString()
        val protocol = WorldEquationEvaluationProtocol(
            version = data.readString(),
            primaryMetric = WorldEquationPrimaryMetric.valueOf(data.readString()),
            minimumIndependentRuns = data.readInt(),
            minimumDistinctWorkloads = data.readInt(),
            minimumActiveObservationsPerChangedCoefficient = data.readInt(),
        )
        val policyFingerprint = data.readString()
        val observationCount = data.readInt()
        require(observationCount in 0..MAX_OBSERVATIONS) {
            "Invalid WorldEquation evidence observation count"
        }
        val observations = buildList(observationCount) {
            repeat(observationCount) {
                add(
                    WorldEquationShadowObservation(
                        caseFingerprint = data.readString(),
                        runId = data.readString(),
                        workloadId = data.readString(),
                        baselineEquationFingerprint = data.readString(),
                        candidateEquationFingerprint = data.readString(),
                        baseline = readMetrics(data),
                        candidate = readMetrics(data),
                    )
                )
            }
        }
        return WorldEquationEvidenceSet(
            candidateVersion = candidateVersion,
            candidateEquationFingerprint = candidateEquationFingerprint,
            candidatePhysicsFingerprint = candidatePhysicsFingerprint,
            baselineVersion = baselineVersion,
            baselineEquationFingerprint = baselineEquationFingerprint,
            baselinePhysicsFingerprint = baselinePhysicsFingerprint,
            equationSchemaFingerprint = schemaFingerprint,
            protocol = protocol,
            policyFingerprint = policyFingerprint,
            observations = observations,
        )
    }

    private fun writeMetrics(
        data: DataOutputStream,
        metrics: WorldEquationRunMetrics,
    ) {
        data.writeString(metrics.status.name)
        data.writeInt(metrics.iterationCount)
        data.writeInt(metrics.conflictCount)
        data.writeInt(metrics.anomalyCount)
        data.writeDouble(metrics.terminalDelta)
        require(metrics.activeCoefficientIds.size <= MAX_ACTIVE_COEFFICIENTS)
        data.writeInt(metrics.activeCoefficientIds.size)
        metrics.activeCoefficientIds.map { it.value }.sorted().forEach { value ->
            data.writeString(value)
        }
    }

    private fun readMetrics(data: DataInputStream): WorldEquationRunMetrics {
        val status = WorldFormulaStatus.valueOf(data.readString())
        val iterationCount = data.readInt()
        val conflictCount = data.readInt()
        val anomalyCount = data.readInt()
        val terminalDelta = data.readDouble()
        val activeCount = data.readInt()
        require(activeCount in 0..MAX_ACTIVE_COEFFICIENTS) {
            "Invalid active WorldEquation coefficient count"
        }
        val active = buildSet {
            repeat(activeCount) {
                add(WorldCoefficientId(data.readString()))
            }
        }
        return WorldEquationRunMetrics(
            status = status,
            iterationCount = iterationCount,
            conflictCount = conflictCount,
            anomalyCount = anomalyCount,
            terminalDelta = terminalDelta,
            activeCoefficientIds = active,
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "WorldEquation evidence string too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) {
            "Invalid WorldEquation evidence string length"
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
