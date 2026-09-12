package app.lifeos.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class V17HardeningEvidenceTest {
    @Test
    fun exactlyOnceEvidenceRejectsDuplicateProductiveEffects() {
        val evidence = sample().copy(duplicateEffectCount = 1)
        assertFailsWith<IllegalArgumentException> {
            V17AcceptanceGate.requirePass(evidence)
        }
    }

    @Test
    fun completeJourneyRequiresTraceResourceAndOutcomeEvidence() {
        val evidence = sample().copy(
            traceIds = listOf("decision-trace:abc"),
            resourceIds = listOf("resource-budget-reservation:abc"),
            outcomeIds = listOf("outcome:abc"),
        )
        assertTrue(
            V17AcceptanceGate.requireCompleteJourneyEvidence(
                V17Journey.GOAL_TO_EXPLANATION,
                evidence,
            ).passesExactlyOnce
        )
    }

    private fun sample() = V17AcceptanceEvidence(
        candidateSha = "0123456789abcdef0123456789abcdef01234567",
        scenarioId = "crash-goal-after-resource-reservation",
        environment = "unit",
        runId = "run-1",
        observedDurableState = "RESERVED",
        duplicateEffectCount = 0,
    )
}
