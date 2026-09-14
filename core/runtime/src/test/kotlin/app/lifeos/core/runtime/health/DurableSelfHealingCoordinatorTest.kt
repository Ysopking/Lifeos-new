package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableSelfHealingCoordinatorTest {
    @Test
    fun `restart verifies in-flight repair and never executes the action twice`() = runTest {
        val healingRepository = MemorySelfHealingRepository()
        val budgetRepository = MemoryBudgetRepository()
        val ledger = SelfHealingLedger(healingRepository) { NOW }
        val budgets = ResourceBudgetCoordinator(budgetRepository) { NOW }
        var executions = 0
        val plan = healthyPlan { executions += 1 }
        val resources = resources()

        val opened = ledger.open(plan, "runtime-failure-1")
        val accountId = ResourceBudgetAccountId("self-healing:${opened.incidentId.value}")
        budgets.createAccount(accountId, resources.hardQuota)
        val reserved = budgets.reserve(
            accountId,
            "self-healing:${opened.incidentId.value}:0:repair-runtime",
            resources.perActionRequested,
        )
        assertIs<app.lifeos.core.runtime.resource.ResourceBudgetReservationResult.Reserved>(reserved)
        ledger.markPrepared(opened, 0, "repair-runtime")

        val coordinator = DurableSelfHealingCoordinator(
            ledger = SelfHealingLedger(healingRepository) { NOW.plusSeconds(1) },
            healthGraph = HealthGraph(now = { NOW.plusSeconds(1) }),
            quarantineRegistry = QuarantineRegistry(),
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW.plusSeconds(1) },
            sharedBudgetProvider = { null },
            now = { NOW.plusSeconds(1) },
        )
        val result = assertIs<DurableSelfHealingResult.Recovered>(
            coordinator.recover(plan, "runtime-failure-1", resources)
        )

        assertTrue(result.recoveredAfterRestart)
        assertEquals(0, executions)
        val account = ResourceBudgetCoordinator(budgetRepository).current(accountId)
        assertEquals(ResourceBudgetReservationState.COMMITTED, account.reservations.single().state)
        assertEquals(resources.perActionRequested, account.consumed)
    }

    @Test
    fun `fresh repair fails closed when shared world budget is unavailable`() = runTest {
        val healingRepository = MemorySelfHealingRepository()
        val budgetRepository = MemoryBudgetRepository()
        var executions = 0
        val plan = healthyPlan { executions += 1 }
        val coordinator = DurableSelfHealingCoordinator(
            ledger = SelfHealingLedger(healingRepository) { NOW },
            healthGraph = HealthGraph(now = { NOW }),
            quarantineRegistry = QuarantineRegistry(),
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            sharedBudgetProvider = { null },
            now = { NOW },
        )

        val blocked = assertIs<DurableSelfHealingResult.Blocked>(
            coordinator.recover(plan, "runtime-failure-2", resources())
        )
        assertTrue(blocked.reason.contains("shared-world-budget-gate-not-installed"))
        assertEquals(0, executions)
        assertEquals(SelfHealingIncidentState.BLOCKED, blocked.incident.state)
    }

    @Test
    fun `terminal recovered incident replays without budget or action work`() = runTest {
        val healingRepository = MemorySelfHealingRepository()
        val budgetRepository = MemoryBudgetRepository()
        val ledger = SelfHealingLedger(healingRepository) { NOW }
        var executions = 0
        val plan = healthyPlan { executions += 1 }
        val opened = ledger.open(plan, "runtime-failure-3")
        val prepared = ledger.markPrepared(opened, 0, "repair-runtime")
        ledger.markRecovered(prepared, "already-healthy", "probe:healthy")

        val replay = assertIs<DurableSelfHealingResult.Recovered>(
            DurableSelfHealingCoordinator(
                ledger = SelfHealingLedger(healingRepository) { NOW.plusSeconds(2) },
                healthGraph = HealthGraph(),
                quarantineRegistry = QuarantineRegistry(),
                budgets = ResourceBudgetCoordinator(budgetRepository),
                sharedBudgetProvider = { null },
            ).recover(plan, "runtime-failure-3", resources())
        )
        assertTrue(replay.replayedTerminal)
        assertEquals(0, executions)
        assertTrue(budgetRepository.accounts.isEmpty())
    }

    @Test
    fun `terminal self healing outcome is projected into the shared lifecycle trace`() = runTest {
        val healingRepository = MemorySelfHealingRepository()
        val budgetRepository = MemoryBudgetRepository()
        val traceRepository = MemoryDecisionTraceRepository()
        val traceLedger = DecisionTraceLedger(traceRepository)
        val plan = healthyPlan {}
        val coordinator = DurableSelfHealingCoordinator(
            ledger = SelfHealingLedger(healingRepository) { NOW },
            healthGraph = HealthGraph(now = { NOW }),
            quarantineRegistry = QuarantineRegistry(),
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            sharedBudgetProvider = { null },
            now = { NOW },
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traceLedger),
        )

        val blocked = assertIs<DurableSelfHealingResult.Blocked>(
            coordinator.recover(plan, "runtime-trace-1", resources())
        )
        val trace = assertNotNull(
            traceLedger.snapshot(
                DecisionTraceId.create("self-healing-incident", blocked.incident.incidentId.value)
            )
        )

        assertTrue(trace.nodes.any {
            it.sourceType == "self-healing-incident" &&
                it.sourceRevision == blocked.incident.ledgerRevision &&
                it.type == DecisionTraceNodeType.REJECTION
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "health-node" && it.sourceId == blocked.incident.nodeId.value
        })
        assertTrue(trace.nodes.any {
            it.reasonCodes.any { reason -> reason.contains("shared-world-budget-gate-not-installed") }
        })
    }

    @Test
    fun `trace persistence failure cannot change self healing result`() = runTest {
        val healingRepository = MemorySelfHealingRepository()
        val budgetRepository = MemoryBudgetRepository()
        val failingTraceLedger = DecisionTraceLedger(
            object : DecisionTraceRepository {
                override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(
                    traces = emptyList(),
                    unreadableEntries = listOf("corrupt-trace"),
                )

                override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean = false
            }
        )
        val plan = healthyPlan {}
        val coordinator = DurableSelfHealingCoordinator(
            ledger = SelfHealingLedger(healingRepository) { NOW },
            healthGraph = HealthGraph(now = { NOW }),
            quarantineRegistry = QuarantineRegistry(),
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            sharedBudgetProvider = { null },
            now = { NOW },
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(failingTraceLedger),
        )

        val blocked = assertIs<DurableSelfHealingResult.Blocked>(
            coordinator.recover(plan, "runtime-trace-2", resources())
        )

        assertEquals(SelfHealingIncidentState.BLOCKED, blocked.incident.state)
        assertTrue(blocked.reason.contains("shared-world-budget-gate-not-installed"))
    }

    private fun healthyPlan(onExecute: () -> Unit) = RecoveryPlan(
        nodeId = HealthNodeId("runtime"),
        source = "self-healing-test",
        actions = listOf(object : RecoveryAction {
            override val id = "repair-runtime"
            override suspend fun execute(): RecoveryActionResult {
                onExecute()
                return RecoveryActionResult.Success("repaired")
            }
        }),
        verificationProbes = listOf(
            WorkerRepairProbe("runtime-probe", HealthNodeId("runtime")) {
                RepairProbeObservation(RepairProbeStatus.HEALTHY)
            }
        ),
    )

    private fun resources(): SelfHealingResourceProfile {
        val usage = ResourceBudgetUsage(
            elapsedMillis = 1_000,
            workUnits = 1,
            memoryBytes = 1_024,
            ioBytes = 1_024,
        )
        return SelfHealingResourceProfile(
            hardQuota = ResourceBudgetQuota(
                elapsedMillis = usage.elapsedMillis,
                workUnits = usage.workUnits,
                memoryBytes = usage.memoryBytes,
                ioBytes = usage.ioBytes,
                networkBytes = 0,
                candidates = 0,
            ),
            perActionRequested = usage,
        )
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

    private class MemoryBudgetRepository : ResourceBudgetRepository {
        val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()
        override suspend fun load(accountId: ResourceBudgetAccountId) =
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

    private class MemoryDecisionTraceRepository : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()

        override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(traces.toList())

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1L)
            traces += trace
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T13:00:00Z")
    }
}
