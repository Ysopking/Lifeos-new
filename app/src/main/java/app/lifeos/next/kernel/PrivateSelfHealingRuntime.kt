package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.health.EncryptedSelfHealingRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeStatus
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.health.AutomaticSelfHealingOrchestrator
import app.lifeos.core.runtime.health.AutomaticSelfHealingPlanBinding
import app.lifeos.core.runtime.health.AutomaticSelfHealingPlanRegistry
import app.lifeos.core.runtime.health.DurableSelfHealingCoordinator
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.health.RecoveryAction
import app.lifeos.core.runtime.health.RecoveryActionResult
import app.lifeos.core.runtime.health.RecoveryPlan
import app.lifeos.core.runtime.health.RepairProbeFact
import app.lifeos.core.runtime.health.RepairProbeObservation
import app.lifeos.core.runtime.health.RepairProbeStatus
import app.lifeos.core.runtime.health.RuntimeRepairProbe
import app.lifeos.core.runtime.health.SelfHealingIncidentGenerationResolver
import app.lifeos.core.runtime.health.SelfHealingLedger
import app.lifeos.core.runtime.health.SelfHealingRepository
import app.lifeos.core.runtime.health.SelfHealingResourceProfile
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import kotlinx.coroutines.CoroutineScope

internal data class PrivateSelfHealingRuntime(
    val repository: SelfHealingRepository,
    val ledger: SelfHealingLedger,
    val coordinator: DurableSelfHealingCoordinator,
    val plans: AutomaticSelfHealingPlanRegistry,
    val generations: SelfHealingIncidentGenerationResolver,
    val orchestrator: AutomaticSelfHealingOrchestrator,
) {
    suspend fun verifyLedgerIntegrity() {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Self-healing ledger contains unreadable entries"
        }
        ledger.active()
    }

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            graph: HealthGraph,
            quarantineRegistry: QuarantineRegistry,
            budgets: ResourceBudgetCoordinator,
            runtime: LifeOsRuntime,
            supervisor: RuntimeSupervisor,
        ): PrivateSelfHealingRuntime {
            val repository = EncryptedSelfHealingRepository(context.applicationContext)
            val ledger = SelfHealingLedger(repository)
            val coordinator = DurableSelfHealingCoordinator(
                ledger = ledger,
                healthGraph = graph,
                quarantineRegistry = quarantineRegistry,
                budgets = budgets,
                lifecycleTraceRecorder = LifecycleDecisionTraceRuntimeRegistry.currentOrNull(),
            )
            val runtimeNode = app.lifeos.core.runtime.health.HealthNodeId("runtime")
            val plans = AutomaticSelfHealingPlanRegistry(
                listOf(
                    AutomaticSelfHealingPlanBinding(
                        id = "runtime-restart-v1",
                        matches = { node, _ -> node.id == runtimeNode && node.scope == HealthScope.RUNTIME },
                        planFactory = { _, _ ->
                            RecoveryPlan(
                                nodeId = runtimeNode,
                                source = "automatic-runtime-self-healing",
                                actions = listOf(
                                    object : RecoveryAction {
                                        override val id: String = "restart-durable-runtime"
                                        override suspend fun execute(): RecoveryActionResult = try {
                                            supervisor.restart()
                                            RecoveryActionResult.Success("durable-runtime-restarted")
                                        } catch (error: Exception) {
                                            RecoveryActionResult.Failure(
                                                message = "runtime-restart:${error.message ?: error::class.simpleName}",
                                                retryable = false,
                                            )
                                        }
                                    }
                                ),
                                verificationProbes = listOf(
                                    RuntimeRepairProbe("runtime-running", runtimeNode) {
                                        val state = runtime.state.value
                                        RepairProbeObservation(
                                            status = if (state.status == RuntimeStatus.RUNNING) {
                                                RepairProbeStatus.HEALTHY
                                            } else {
                                                RepairProbeStatus.UNHEALTHY
                                            },
                                            message = "runtime-status:${state.status.name.lowercase()}",
                                            facts = listOf(
                                                RepairProbeFact("status", state.status.name),
                                            ),
                                        )
                                    }
                                ),
                                quarantineOnFailure = true,
                            )
                        },
                        resources = SelfHealingResourceProfile(
                            hardQuota = ResourceBudgetQuota(
                                elapsedMillis = 20_000,
                                workUnits = 4,
                                memoryBytes = 16L * 1024L * 1024L,
                                ioBytes = 2L * 1024L * 1024L,
                                networkBytes = 0,
                                candidates = 1,
                            ),
                            perActionRequested = ResourceBudgetUsage(
                                elapsedMillis = 10_000,
                                workUnits = 2,
                                memoryBytes = 8L * 1024L * 1024L,
                                ioBytes = 1L * 1024L * 1024L,
                                networkBytes = 0,
                                candidates = 1,
                            ),
                            goalRelevance = 1.0,
                            priority = 1.0,
                            expectedUtility = 0.95,
                            confidence = 0.95,
                        ),
                    )
                )
            )
            val generations = SelfHealingIncidentGenerationResolver(ledger)
            val orchestrator = AutomaticSelfHealingOrchestrator(
                scope = scope,
                graph = graph,
                plans = plans,
                generations = generations,
                coordinator = coordinator,
                quarantineRegistry = quarantineRegistry,
            )
            return PrivateSelfHealingRuntime(
                repository = repository,
                ledger = ledger,
                coordinator = coordinator,
                plans = plans,
                generations = generations,
                orchestrator = orchestrator,
            )
        }
    }
}
