package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SelfHealingIncidentGenerationResolverTest {
    @Test
    fun `unfinished incident generation resumes after reconstruction`() = runTest {
        val repository = MemoryRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val resolver = SelfHealingIncidentGenerationResolver(ledger)
        val plan = plan()

        val first = assertIs<SelfHealingGenerationResolution.Ready>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        val opened = ledger.open(plan, first.incidentFingerprint)
        ledger.markPrepared(opened, 0, "repair")

        val reconstructed = SelfHealingIncidentGenerationResolver(SelfHealingLedger(repository))
        val resumed = assertIs<SelfHealingGenerationResolution.Ready>(
            reconstructed.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        assertEquals(1, resumed.generation)
        assertEquals(true, resumed.resumedExisting)
        assertEquals(first.incidentFingerprint, resumed.incidentFingerprint)
    }

    @Test
    fun `recovered family advances to next generation on a new failure`() = runTest {
        val repository = MemoryRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val resolver = SelfHealingIncidentGenerationResolver(ledger)
        val plan = plan()
        val first = assertIs<SelfHealingGenerationResolution.Ready>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        val prepared = ledger.markPrepared(ledger.open(plan, first.incidentFingerprint), 0, "repair")
        ledger.markRecovered(prepared, "ok", "probe:healthy")

        val second = assertIs<SelfHealingGenerationResolution.Ready>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        assertEquals(2, second.generation)
        assertEquals(false, second.resumedExisting)
    }

    @Test
    fun `quarantined family is suppressed across restart until a healthy epoch exists`() = runTest {
        val repository = MemoryRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val resolver = SelfHealingIncidentGenerationResolver(ledger)
        val plan = plan()
        val first = assertIs<SelfHealingGenerationResolution.Ready>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        val opened = ledger.open(plan, first.incidentFingerprint)
        val exhausted = ledger.markExhausted(opened, "failed")
        ledger.markQuarantined(exhausted, "failed")

        assertIs<SelfHealingGenerationResolution.Suppressed>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = false)
        )
        val afterHealthy = assertIs<SelfHealingGenerationResolution.Ready>(
            resolver.resolve(plan, FAMILY, healthyObservedSinceStartup = true)
        )
        assertEquals(2, afterHealthy.generation)
    }

    private fun plan() = RecoveryPlan(
        nodeId = HealthNodeId("runtime"),
        source = "test",
        actions = listOf(object : RecoveryAction {
            override val id = "repair"
            override suspend fun execute() = RecoveryActionResult.Success()
        }),
        verificationProbes = listOf(
            RuntimeRepairProbe("probe", HealthNodeId("runtime")) {
                RepairProbeObservation(RepairProbeStatus.HEALTHY)
            }
        ),
    )

    private class MemoryRepository : SelfHealingRepository {
        private val events = mutableListOf<SelfHealingEvent>()
        override suspend fun loadReport() = SelfHealingRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: SelfHealingEvent): Boolean {
            val revision = events.lastOrNull()?.revision ?: 0L
            if (revision != expectedRevision) return false
            events += event
            return true
        }
    }

    private companion object {
        const val FAMILY = "runtime-family"
        val NOW: Instant = Instant.parse("2026-09-11T13:10:00Z")
    }
}
