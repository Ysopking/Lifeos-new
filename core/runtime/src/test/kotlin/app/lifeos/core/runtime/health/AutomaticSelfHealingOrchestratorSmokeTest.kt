package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import java.time.Instant
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
            budgets = ResourceBudgetCoordinator(MemoryResourceBudgetRepository()),
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
        runCurrent()
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
        runCurrent()
        assertNotNull(quarantine.active(nodeId, NOW))
        assertEquals(HealthState.QUARANTINED, graph.node(nodeId)?.state)
    }

    @Test
    fun `non-actionable health evidence remains observable without repair or quarantine`() = runTest {
        val graph = HealthGraph()
        val quarantine = QuarantineRegistry()
        val ledger = SelfHealingLedger(MemorySelfHealingRepository()) { NOW }
        val coordinator = DurableSelfHealingCoordinator(
            ledger = ledger,
            healthGraph = graph,
            quarantineRegistry = quarantine,
            budgets = ResourceBudgetCoordinator(MemoryResourceBudgetRepository()),
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
        runCurrent()

        val nodeId = HealthNodeId("diagnostic:self-observation")
        graph.register(nodeId, HealthScope.RUNTIME)
        graph.record(
            HealthObservation(
                nodeId = nodeId,
                state = HealthState.UNHEALTHY,
                observedAt = NOW,
                source = "diagnostic",
                message = "observed-only",
                actionable = false,
            )
        )
        runCurrent()

        assertNull(quarantine.active(nodeId, NOW))
        assertEquals(HealthState.UNHEALTHY, graph.node(nodeId)?.state)
        assertEquals(0, ledger.active().size)
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

    private class MemoryResourceBudgetRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (account.id in accounts) return false
            accounts[account.id] = account
            return true
        }

        override suspend fun compareAndSet(
            accountId: ResourceBudgetAccountId,
            expectedRevision: Long,
            updated: ResourceBudgetAccount,
        ): Boolean {
            val current = accounts[accountId] ?: return false
            if (current.revision != expectedRevision) return false
            accounts[accountId] = updated
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T13:35:00Z")
    }
}
