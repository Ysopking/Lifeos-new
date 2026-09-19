package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.FailureClassification
import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNode
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthObservation
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AutomaticHealthEscalationOrchestratorTest {
    @Test
    fun recoverableFailureWithPlanRoutesToL2() = runTest {
        val selected = mutableListOf<EscalationLevel>()
        val graph = HealthGraph()
        orchestrator(graph, recoveryAvailable = true, selected).start()
        runCurrent()

        recordFailure(
            graph = graph,
            nodeId = HealthNodeId("worker:l2"),
            scope = HealthScope.WORKER,
            category = HealthFailureCategory.WORKER,
            recoverable = true,
        )
        runCurrent()

        assertEquals(listOf(EscalationLevel.L2_RECOVER_COMPONENT), selected)
    }

    @Test
    fun failedL2RecoveryEscalatesThroughCentralPolicyToL3() = runTest {
        val selected = mutableListOf<EscalationLevel>()
        val graph = HealthGraph()
        val ledger = EscalationLedger(MemoryEscalationRepository()) { NOW }
        val executors = EscalationExecutorRegistry(
            EscalationLevel.entries.associateWith { level ->
                EscalationLevelExecutor {
                    selected += level
                    if (level == EscalationLevel.L2_RECOVER_COMPONENT) {
                        EscalationExecutionResult.Failed("repair-exhausted")
                    } else {
                        EscalationExecutionResult.Succeeded("executed:" + level.name)
                    }
                }
            }
        )
        AutomaticHealthEscalationOrchestrator(
            scope = backgroundScope,
            graph = graph,
            recoveryPlanner = object : HealthEscalationRecoveryPlanner {
                override fun observeHealthy(nodeId: HealthNodeId) = Unit
                override suspend fun recoveryAvailable(
                    node: HealthNode,
                    observation: HealthObservation,
                ): Boolean = true
            },
            coordinator = EscalationCoordinator(
                policy = EscalationPolicy(),
                ledger = ledger,
                executors = executors,
            ),
        ).start()
        runCurrent()

        recordFailure(
            graph = graph,
            nodeId = HealthNodeId("worker:l2-to-l3"),
            scope = HealthScope.WORKER,
            category = HealthFailureCategory.WORKER,
            recoverable = true,
        )
        runCurrent()

        assertEquals(
            listOf(
                EscalationLevel.L2_RECOVER_COMPONENT,
                EscalationLevel.L3_QUARANTINE,
            ),
            selected,
        )
    }

    @Test
    fun recoverableFailureWithoutSafePlanRoutesDirectlyToL3() = runTest {
        val selected = mutableListOf<EscalationLevel>()
        val graph = HealthGraph()
        orchestrator(graph, recoveryAvailable = false, selected).start()
        runCurrent()

        recordFailure(
            graph = graph,
            nodeId = HealthNodeId("external:no-plan"),
            scope = HealthScope.EXTERNAL_APP,
            category = HealthFailureCategory.DEPENDENCY,
            recoverable = true,
        )
        runCurrent()

        assertEquals(listOf(EscalationLevel.L3_QUARANTINE), selected)
    }

    @Test
    fun nonActionableHealthEvidenceDoesNotEnterEscalationLadder() = runTest {
        val selected = mutableListOf<EscalationLevel>()
        val graph = HealthGraph()
        orchestrator(graph, recoveryAvailable = true, selected).start()
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

        assertEquals(emptyList(), selected)
        assertEquals(HealthState.UNHEALTHY, graph.node(nodeId)?.state)
    }

    @Test
    fun protectionCriticalStorageCorruptionJumpsToL6() = runTest {
        val selected = mutableListOf<EscalationLevel>()
        val graph = HealthGraph()
        orchestrator(graph, recoveryAvailable = true, selected).start()
        runCurrent()

        recordFailure(
            graph = graph,
            nodeId = HealthNodeId("storage:critical"),
            scope = HealthScope.STORAGE_ENGINE,
            category = HealthFailureCategory.DATA_CORRUPTION,
            recoverable = false,
        )
        runCurrent()

        assertEquals(listOf(EscalationLevel.L6_SAFE_MODE), selected)
    }

    private fun TestScope.orchestrator(
        graph: HealthGraph,
        recoveryAvailable: Boolean,
        selected: MutableList<EscalationLevel>,
    ): AutomaticHealthEscalationOrchestrator {
        val ledger = EscalationLedger(MemoryEscalationRepository()) { NOW }
        val executors = EscalationExecutorRegistry(
            EscalationLevel.entries.associateWith { level ->
                EscalationLevelExecutor {
                    selected += level
                    EscalationExecutionResult.Succeeded("executed:" + level.name)
                }
            }
        )
        return AutomaticHealthEscalationOrchestrator(
            scope = backgroundScope,
            graph = graph,
            recoveryPlanner = object : HealthEscalationRecoveryPlanner {
                override fun observeHealthy(nodeId: HealthNodeId) = Unit

                override suspend fun recoveryAvailable(
                    node: HealthNode,
                    observation: HealthObservation,
                ): Boolean = recoveryAvailable
            },
            coordinator = EscalationCoordinator(
                policy = EscalationPolicy(),
                ledger = ledger,
                executors = executors,
            ),
        )
    }

    private suspend fun recordFailure(
        graph: HealthGraph,
        nodeId: HealthNodeId,
        scope: HealthScope,
        category: HealthFailureCategory,
        recoverable: Boolean,
    ) {
        graph.register(nodeId, scope)
        graph.record(
            HealthObservation(
                nodeId = nodeId,
                state = HealthState.UNHEALTHY,
                observedAt = NOW,
                source = "test",
                message = "failure",
                classification = FailureClassification(
                    category = category,
                    scope = scope,
                    recoverable = recoverable,
                    suggestedState = HealthState.UNHEALTHY,
                ),
            )
        )
    }

    private class MemoryEscalationRepository : EscalationRepository {
        private val records = mutableListOf<EscalationRecord>()

        override suspend fun loadReport(): EscalationRepositoryLoadReport =
            EscalationRepositoryLoadReport(records.toList())

        override suspend fun append(
            expectedRevision: Long,
            record: EscalationRecord,
        ): Boolean {
            val current = records.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            records += record
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T01:20:00Z")
    }
}
