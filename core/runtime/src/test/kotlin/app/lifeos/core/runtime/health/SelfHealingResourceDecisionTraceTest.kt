package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.HardwareAdaptiveResourceOptimizer
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetDomainAllocation
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.WorldFormulaBudgetAllocationPlan
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.selfHealingDecisionTraceId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelfHealingResourceDecisionTraceTest {
    @Test
    fun `reservation and settlement join the canonical self healing trace`() = runTest {
        val traceRepository = MemoryDecisionTraceRepository()
        val traceLedger = DecisionTraceLedger(traceRepository)
        val resources = resources()
        val coordinator = DurableSelfHealingCoordinator(
            ledger = SelfHealingLedger(MemorySelfHealingRepository()) { NOW },
            healthGraph = HealthGraph(now = { NOW }),
            quarantineRegistry = QuarantineRegistry(),
            budgets = ResourceBudgetCoordinator(MemoryBudgetRepository()) { NOW },
            sharedBudgetProvider = { readyGate(resources) },
            now = { NOW },
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traceLedger),
        )
        val plan = RecoveryPlan(
            nodeId = HealthNodeId("runtime"),
            source = "resource-trace-test",
            actions = listOf(
                object : RecoveryAction {
                    override val id = "repair-runtime"
                    override suspend fun execute() = RecoveryActionResult.Success("repaired")
                }
            ),
            verificationProbes = listOf(
                WorkerRepairProbe("runtime-probe", HealthNodeId("runtime")) {
                    RepairProbeObservation(RepairProbeStatus.HEALTHY)
                }
            ),
        )

        val recovered = assertIs<DurableSelfHealingResult.Recovered>(
            coordinator.recover(plan, "resource-trace-incident", resources)
        )
        val trace = assertNotNull(
            traceLedger.snapshot(selfHealingDecisionTraceId(recovered.incident.incidentId.value))
        )
        val authoritativeOutcomeId =
            "self-healing:${recovered.incident.incidentId.value}:ledger-${recovered.incident.ledgerRevision}"

        assertTrue(trace.nodes.any {
            it.sourceType == "resource-reservation" &&
                "DOMAIN_SELF_HEALING" in it.reasonCodes &&
                "RESERVED" in it.reasonCodes
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "resource-reservation" &&
                "DOMAIN_SELF_HEALING" in it.reasonCodes &&
                "STATE_COMMITTED" in it.reasonCodes
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "resource-authoritative-outcome" &&
                it.sourceId == authoritativeOutcomeId &&
                "RESOURCE_SETTLEMENT_COMMITTED" in it.reasonCodes
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "self-healing-incident" &&
                it.sourceId == recovered.incident.incidentId.value
        })
    }

    private fun readyGate(resources: SelfHealingResourceProfile): SharedResourceBudgetGate =
        SharedResourceBudgetGate { hardQuota, demands ->
            val demand = demands.single()
            val hardware = HardwareStateSnapshot(
                observedAt = NOW,
                availableProcessors = 8,
                batteryFraction = 1.0,
                charging = true,
                thermalState = HardwareThermalState.NOMINAL,
                cpuLoadFraction = 0.0,
                availableMemoryBytes = 1_024,
                totalMemoryBytes = 1_024,
                availableStorageBytes = 1_024,
                totalStorageBytes = 1_024,
            )
            val hardwarePlan = HardwareAdaptiveResourceOptimizer().plan(
                hardQuota = hardQuota,
                requested = resources.perActionRequested,
                hardware = hardware,
            )
            SharedResourceBudgetDecision.Ready(
                hardwarePlan = hardwarePlan,
                allocation = WorldFormulaBudgetAllocationPlan(
                    pool = hardQuota,
                    allocations = listOf(
                        ResourceBudgetDomainAllocation(
                            domain = ResourceBudgetDomain.SELF_HEALING,
                            demandFingerprint = demand.fingerprint(),
                            worldWeight = 1.0,
                            allocated = resources.perActionRequested,
                        )
                    ),
                    unallocated = ResourceBudgetUsage(),
                    worldSnapshotId = "world-resource-trace-test",
                    hardwareSnapshotFingerprint = hardware.fingerprint(),
                ),
            )
        }

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
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

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
        val NOW: Instant = Instant.parse("2026-09-14T10:30:00Z")
    }
}
