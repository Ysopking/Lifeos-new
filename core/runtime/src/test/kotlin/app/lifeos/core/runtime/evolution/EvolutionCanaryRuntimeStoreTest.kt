package app.lifeos.core.runtime.evolution

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EvolutionCanaryRuntimeStoreTest {
    @Test
    fun `kill switch atomically blocks later reservation`() = runBlocking {
        val store = InMemoryEvolutionCanaryBudgetStore()
        val stop = EvolutionCanaryKillSwitchEvidence(
            adoptionEvidenceId = "adoption-j07",
            candidateToolId = "tool-j07",
            reason = EvolutionCanaryStopReason.HARD_FAILURE,
            triggerOutcomeId = "outcome-j07",
            hardFailures = setOf(EvolutionHardFailure.SAFETY_VIOLATION),
            trippedAt = Instant.parse("2026-09-10T15:00:00Z"),
        )
        store.trip(stop)

        val result = store.reserve(
            adoptionEvidenceId = stop.adoptionEvidenceId,
            invocationId = "after-stop",
            maxInvocations = 10,
            reservedAt = stop.trippedAt.plusSeconds(1),
        )

        val stopped = assertIs<EvolutionCanaryReserveResult.Stopped>(result)
        assertEquals(stop.id, stopped.killSwitch.id)
        assertEquals(0, store.usedInvocations(stop.adoptionEvidenceId))
    }

    @Test
    fun `first kill switch evidence remains authoritative`() = runBlocking {
        val store = InMemoryEvolutionCanaryBudgetStore()
        val first = EvolutionCanaryKillSwitchEvidence(
            adoptionEvidenceId = "adoption-j07",
            candidateToolId = "tool-j07",
            reason = EvolutionCanaryStopReason.HARD_FAILURE,
            triggerOutcomeId = "outcome-first",
            hardFailures = setOf(EvolutionHardFailure.CONTRACT_VIOLATION),
            trippedAt = Instant.parse("2026-09-10T15:00:00Z"),
        )
        val second = first.copy(
            triggerOutcomeId = "outcome-second",
            hardFailures = setOf(EvolutionHardFailure.DATA_CORRUPTION),
            trippedAt = first.trippedAt.plusSeconds(1),
        )

        assertEquals(first.id, store.trip(first).id)
        assertEquals(first.id, store.trip(second).id)
        assertEquals(first.id, store.killSwitch(first.adoptionEvidenceId)?.id)
    }
}
