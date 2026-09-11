package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.InMemoryResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AutomaticSelfHealingOrchestratorSmokeTest {
    @Test
    fun `unregistered unsafe failure is quarantined without repair`() = runTest {
        val graph = HealthGraph()
        val quarantine = QuarantineRegistry()
        val ledger = SelfHealingLedger(MemorySelfHealingRepository()) { NOW }
        val coordinator = DurableSelfHealingCoordinator(
            ledger = ledger,
            healthGraph = graph,
            quarantineRegistry = quarantine,
            budgets = ResourceBudgetCoordinator(InMemoryResourceBudgetRepository()),
            sharedBudgetProvider = { null },
            now = { NOW },
        )
        val orchestrator = AutomaticSelfHealingOrchestrator(
            scope = backgroundScope,
            graph = graph,
            plans = AutomaticSelfHealingPlanRegistry(emptyList()),
            generations = SelfHealingIncidentGenerationResolver(ledger),
            coordinator = coordinator,
            quarantineRegistry = quarantine,
            now = { NOW },
        )
        orchestrator.start()
        val nodeId = HealthNodeId("external:unsafe")
        graph.register(nodeId, HealthScope.EXTERNAL_APP)
        graph.record(
            HealthObservation(
                nodeId = nodeId,
                state = HealthState.UNHEALTHY,
                observedAt = NOW,
                source = "external",
                message = "unsafe",
                classification = FailureClassification(
                    HealthFailureCategory.DEPENDENCY,
                    HealthScope.EXTERNAL_APP,
                    recoverable = true,
                    suggestedState = HealthState.UNHEALTHY,
                ),
            )
        )
        advanceUntilIdle()
        assertNotNull(quarantine.active(nodeId, NOW))
        assertEquals(HealthState.QUARANTINED, graph.node(nodeId)?.state)
    }

    private class MemorySelfHealingRepository : SelfHealingRepository {
        private val events = mutableListOf<SelfHealingEvent>()
        override suspend fun loadReport() = SelfHealingRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: SelfHealingEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T13:35:00Z")
    }
}
