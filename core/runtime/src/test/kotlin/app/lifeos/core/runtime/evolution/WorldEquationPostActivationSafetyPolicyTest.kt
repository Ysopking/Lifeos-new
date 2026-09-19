package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.world.SelfStateWorldBand
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldEquationPostActivationSafetyPolicyTest {
    private val policy = WorldEquationPostActivationSafetyPolicy.V1

    @Test
    fun oneCriticalObservationNeverRollsBack() {
        assertFalse(
            policy.requiresRollback(
                listOf(observation("one", SelfStateWorldBand.CRITICAL))
            )
        )
    }

    @Test
    fun persistentCriticalStateRollsBackOnlyAfterMinimumEvidence() {
        val observations = listOf(
            observation("stable", SelfStateWorldBand.STABLE),
            observation("critical-1", SelfStateWorldBand.CRITICAL),
            observation("critical-2", SelfStateWorldBand.CRITICAL),
        )
        assertTrue(policy.requiresRollback(observations))
    }

    @Test
    fun threeConsecutiveDegradedStatesTriggerRollback() {
        val observations = listOf(
            observation("degraded-1", SelfStateWorldBand.DEGRADED),
            observation("degraded-2", SelfStateWorldBand.DEGRADED),
            observation("degraded-3", SelfStateWorldBand.DEGRADED),
        )
        assertTrue(policy.requiresRollback(observations))
    }

    @Test
    fun stableRecoveryBreaksDegradedPersistence() {
        val observations = listOf(
            observation("degraded-1", SelfStateWorldBand.DEGRADED),
            observation("stable", SelfStateWorldBand.STABLE),
            observation("degraded-2", SelfStateWorldBand.DEGRADED),
        )
        assertFalse(policy.requiresRollback(observations))
    }

    private fun observation(
        id: String,
        band: SelfStateWorldBand,
    ) = WorldEquationPostActivationSafetyObservation.create(
        candidateEquationFingerprint = "candidate-fingerprint",
        assessmentId = "assessment-" + id,
        authorityFingerprint = "authority-" + id,
        band = band,
        observedAt = Instant.parse("2026-09-19T07:30:00Z").plusSeconds(id.length.toLong()),
    )
}
