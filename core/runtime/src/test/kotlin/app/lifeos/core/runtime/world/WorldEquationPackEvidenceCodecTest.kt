package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldEquationPackEvidenceCodecTest {
    @Test
    fun codecRoundTripPreservesStructuralEvidenceIdentity() {
        val record = record()

        val decoded = WorldEquationPackEvidenceCodec.decode(
            WorldEquationPackEvidenceCodec.encode(record)
        )

        assertEquals(record, decoded)
        assertEquals(record.fingerprint, decoded.fingerprint)
        assertEquals(record.evidence.fingerprint(), decoded.evidence.fingerprint())
    }

    @Test
    fun codecRejectsTrailingBytes() {
        val encoded = WorldEquationPackEvidenceCodec.encode(record())
        val malformed = encoded + byteArrayOf(0x01)

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackEvidenceCodec.decode(malformed)
        }
    }

    private fun record(): WorldEquationPackEvidenceRecord {
        val baselinePackFingerprint = "baseline-pack-fingerprint"
        val candidatePackFingerprint = "candidate-pack-fingerprint"
        val observation = WorldEquationPackShadowObservation(
            caseFingerprint = "case-fingerprint",
            runId = "run-1",
            workloadId = "workload-1",
            partition = WorldEquationPackEvidencePartition.SHADOW,
            baselinePackFingerprint = baselinePackFingerprint,
            candidatePackFingerprint = candidatePackFingerprint,
            baseline = metrics(
                materialization = "baseline-materialization",
                graph = "baseline-graph",
                finalState = "baseline-final-state",
            ),
            candidate = metrics(
                materialization = "candidate-materialization",
                graph = "candidate-graph",
                finalState = "candidate-final-state",
            ),
        )
        val evidence = WorldEquationPackEvidenceSet(
            candidatePackVersion = "pack-v2",
            candidatePackFingerprint = candidatePackFingerprint,
            candidateStructuralFingerprint = "candidate-structure",
            baselinePackVersion = "pack-v1",
            baselinePackFingerprint = baselinePackFingerprint,
            baselineStructuralFingerprint = "baseline-structure",
            structuralPreflightId = "preflight-1",
            registrySnapshotId = "registry-1",
            registryFingerprint = "registry-fingerprint",
            protocol = WorldEquationPackEvaluationProtocol(
                version = "protocol-v1",
                minimumIndependentRuns = 2,
                minimumDistinctWorkloads = 1,
                minimumStructuralExerciseRuns = 1,
                minimumShadowRuns = 1,
                minimumHoldoutRuns = 1,
            ),
            observations = listOf(observation),
        )
        return WorldEquationPackEvidenceRecord.create(
            revision = 2L,
            state = WorldEquationPackLifecycleState.SHADOW,
            evidence = evidence,
            latestAssessmentId = "assessment-1",
        )
    }

    private fun metrics(
        materialization: String,
        graph: String,
        finalState: String,
    ) = WorldEquationPackRunMetrics(
        status = WorldFormulaStatus.CONVERGED,
        iterationCount = 2,
        conflictCount = 0,
        anomalyCount = 0,
        terminalDelta = 0.0,
        activeCoefficientIds = setOf(WorldCoefficientId("coefficient-1")),
        materializationFingerprint = materialization,
        graphFingerprint = graph,
        finalStateFingerprint = finalState,
    )
}
