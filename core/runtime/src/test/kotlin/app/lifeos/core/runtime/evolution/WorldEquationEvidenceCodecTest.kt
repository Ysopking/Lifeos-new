package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldEquationEvidenceCodecTest {
    @Test
    fun roundTripPreservesRecordIdentity() {
        val baseline = CognitiveWorldEquationProfile().spec
        val first = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = baseline.version + "-candidate",
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else {
                    it
                }
            },
        )
        val protocol = WorldEquationEvaluationProtocol(
            version = "codec-test-v1",
            primaryMetric = WorldEquationPrimaryMetric.CONFLICT_COUNT,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )
        val evidence = WorldEquationEvidenceSet.empty(
            candidate = candidate,
            baseline = baseline,
            protocol = protocol,
            policyFingerprint = WorldEquationPromotionPolicy.V1.fingerprint(),
        )
        val record = WorldEquationEvidenceRecord.create(
            revision = 1L,
            state = WorldEquationLifecycleState.CONJECTURE,
            evidence = evidence,
        )

        val decoded = WorldEquationEvidenceCodec.decode(
            WorldEquationEvidenceCodec.encode(record)
        )

        assertEquals(record, decoded)
        assertEquals(record.id, decoded.id)
        assertEquals(record.fingerprint, decoded.fingerprint)
    }
}
