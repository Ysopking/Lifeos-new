package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackStructuralCanaryEvidenceCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_REPLAYS = 4096
    private const val MAX_ACTIVE_COEFFICIENTS = 4096

    fun encode(
        record: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): ByteArray {
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
                "Structural canary evidence payload size is invalid"
            }
        }
    }

    fun decode(
        bytes: ByteArray,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "Structural canary evidence payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported structural canary evidence codec"
            }
            val storedId = data.readString()
            val revision = data.readLong()
            val state = WorldEquationPackStructuralCanaryLifecycleState.valueOf(
                data.readString()
            )
            val evidence = readEvidence(data)
            val latestAssessmentId = data.readNullableString()
            val storedFingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing structural canary evidence bytes"
            }

            WorldEquationPackStructuralCanaryEvidenceRecord.create(
                revision = revision,
                state = state,
                evidence = evidence,
                latestAssessmentId = latestAssessmentId,
            ).also { record ->
                require(record.id == storedId) {
                    "Structural canary evidence id does not match content"
                }
                require(record.fingerprint == storedFingerprint) {
                    "Structural canary evidence fingerprint does not match content"
                }
            }
        }
    }

    private fun writeEvidence(
        data: DataOutputStream,
        evidence: WorldEquationPackStructuralCanaryEvidenceSet,
    ) {
        data.writeString(evidence.planFingerprint)
        data.writeString(evidence.candidatePackFingerprint)
        data.writeString(evidence.protocol.version)
        data.writeInt(evidence.protocol.minimumIndependentCases)
        data.writeInt(evidence.protocol.minimumShadowReferences)
        data.writeInt(evidence.protocol.minimumHoldoutReferences)
        require(evidence.replays.size <= MAX_REPLAYS)
        data.writeInt(evidence.replays.size)
        evidence.replays
            .sortedBy { it.reference.caseFingerprint }
            .forEach { replay ->
                writeShadowObservation(data, replay.reference)
                writeCanaryObservation(data, replay.canary)
            }
    }

    private fun readEvidence(
        data: DataInputStream,
    ): WorldEquationPackStructuralCanaryEvidenceSet {
        val planFingerprint = data.readString()
        val candidatePackFingerprint = data.readString()
        val protocol = WorldEquationPackStructuralCanaryProtocol(
            version = data.readString(),
            minimumIndependentCases = data.readInt(),
            minimumShadowReferences = data.readInt(),
            minimumHoldoutReferences = data.readInt(),
        )
        val count = data.readInt()
        require(count in 0..MAX_REPLAYS) {
            "Invalid structural canary replay count"
        }
        val replays = buildList(count) {
            repeat(count) {
                add(
                    WorldEquationPackStructuralCanaryReplay(
                        reference = readShadowObservation(data),
                        canary = readCanaryObservation(data),
                    )
                )
            }
        }
        return WorldEquationPackStructuralCanaryEvidenceSet(
            planFingerprint = planFingerprint,
            candidatePackFingerprint = candidatePackFingerprint,
            protocol = protocol,
            replays = replays,
        )
    }

    private fun writeShadowObservation(
        data: DataOutputStream,
        observation: WorldEquationPackShadowObservation,
    ) {
        data.writeString(observation.caseFingerprint)
        data.writeString(observation.runId)
        data.writeString(observation.workloadId)
        data.writeString(observation.partition.name)
        data.writeString(observation.baselinePackFingerprint)
        data.writeString(observation.candidatePackFingerprint)
        writeMetrics(data, observation.baseline)
        writeMetrics(data, observation.candidate)
    }

    private fun readShadowObservation(
        data: DataInputStream,
    ): WorldEquationPackShadowObservation =
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

    private fun writeCanaryObservation(
        data: DataOutputStream,
        observation: WorldEquationPackStructuralCanaryObservation,
    ) {
        data.writeString(observation.caseFingerprint)
        data.writeString(observation.planFingerprint)
        data.writeString(observation.admissionFingerprint)
        data.writeString(observation.candidatePackFingerprint)
        writeMetrics(data, observation.metrics)
        data.writeString(observation.fingerprint)
    }

    private fun readCanaryObservation(
        data: DataInputStream,
    ): WorldEquationPackStructuralCanaryObservation =
        WorldEquationPackStructuralCanaryObservation(
            caseFingerprint = data.readString(),
            planFingerprint = data.readString(),
            admissionFingerprint = data.readString(),
            candidatePackFingerprint = data.readString(),
            metrics = readMetrics(data),
            fingerprint = data.readString(),
        )

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
        metrics.activeCoefficientIds
            .map { it.value }
            .sorted()
            .forEach(data::writeString)
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
            "Invalid structural canary active coefficient count"
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
            "Structural canary evidence string too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) {
            "Invalid structural canary evidence string length"
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
