package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class EscalationCoordinatorTest {
    private val at = Instant.parse("2026-09-19T00:40:00Z")

    @Test
    fun freshEscalationExecutesPolicySelectedLevelAndPersistsTerminalOutcome() = runBlocking {
        val repository = MemoryRepository()
        val ledger = EscalationLedger(repository)
        var executedLevel: EscalationLevel? = null
        val coordinator = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = ledger,
            executors = registry { request ->
                executedLevel = request.level
                EscalationExecutionResult.Succeeded("recovery-complete", setOf("proof-1"))
            },
        )

        val result = assertIs<EscalationCoordinationResult.Completed>(
            coordinator.coordinate(trigger())
        )

        assertEquals(EscalationLevel.L2_RECOVER_COMPONENT, executedLevel)
        assertEquals(EscalationState.ACTION_SUCCEEDED, result.snapshot.state)
        assertTrue("proof-1" in result.snapshot.evidenceRefs)
        assertTrue(!result.resumed)
    }

    @Test
    fun cancellationLeavesInFlightAndRestartReusesSameExecutionIdentity() = runBlocking {
        val repository = MemoryRepository()
        val trigger = trigger()
        var firstExecutionId: EscalationExecutionId? = null
        val first = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry { request ->
                firstExecutionId = request.executionId
                throw CancellationException("process-death")
            },
        )
        assertIs<CancellationException>(
            runCatching { first.coordinate(trigger) }.exceptionOrNull()
        )
        assertEquals(
            EscalationState.ACTION_IN_FLIGHT,
            EscalationLedger(repository).snapshot(trigger.id)?.state,
        )

        var resumedRequest: EscalationExecutionRequest? = null
        val second = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry { request ->
                resumedRequest = request
                EscalationExecutionResult.Succeeded("verified-after-restart")
            },
        )
        val resumed = assertIs<EscalationCoordinationResult.Completed>(
            second.coordinate(trigger)
        )

        assertEquals(firstExecutionId, resumed.executionId)
        assertEquals(firstExecutionId, resumedRequest?.executionId)
        assertTrue(resumedRequest?.resuming == true)
        assertTrue(resumed.resumed)
        assertEquals(EscalationState.ACTION_SUCCEEDED, resumed.snapshot.state)
    }

    @Test
    fun activeInFlightEscalationResumesByNodeWithoutOriginalTrigger() = runBlocking {
        val repository = MemoryRepository()
        val trigger = trigger()
        var originalExecutionId: EscalationExecutionId? = null
        val first = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry { request ->
                originalExecutionId = request.executionId
                throw CancellationException("simulated-process-death")
            },
        )
        assertIs<CancellationException>(
            runCatching { first.coordinate(trigger) }.exceptionOrNull()
        )

        var resumedRequest: EscalationExecutionRequest? = null
        val second = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry { request ->
                resumedRequest = request
                EscalationExecutionResult.Succeeded("resumed-without-trigger")
            },
        )
        val resumed = second.resumeActive(trigger.nodeId)

        assertEquals(1, resumed.size)
        val completed = assertIs<EscalationCoordinationResult.Completed>(resumed.single())
        assertEquals(originalExecutionId, completed.executionId)
        assertEquals(originalExecutionId, resumedRequest?.executionId)
        assertTrue(completed.resumed)
        assertTrue(resumedRequest?.resuming == true)
        assertEquals(EscalationState.ACTION_SUCCEEDED, completed.snapshot.state)
    }

    @Test
    fun terminalEscalationIsReplayedWithoutExecutingAgain() = runBlocking {
        val repository = MemoryRepository()
        val trigger = trigger()
        var calls = 0
        val coordinator = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry {
                calls += 1
                EscalationExecutionResult.Succeeded("done")
            },
        )
        assertIs<EscalationCoordinationResult.Completed>(coordinator.coordinate(trigger))
        assertIs<EscalationCoordinationResult.ReplayedTerminal>(coordinator.coordinate(trigger))
        assertEquals(1, calls)
    }

    @Test
    fun executorExceptionBecomesDurableFailureInsteadOfEscaping() = runBlocking {
        val repository = MemoryRepository()
        val coordinator = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository),
            executors = registry { error("boom") },
        )

        val result = assertIs<EscalationCoordinationResult.Completed>(
            coordinator.coordinate(trigger())
        )

        assertEquals(EscalationState.ACTION_FAILED, result.snapshot.state)
        val failed = assertIs<EscalationExecutionResult.Failed>(result.execution)
        assertTrue(failed.detail.startsWith("executor-exception:"))
    }

    @Test
    fun registryRejectsMissingLevels() {
        assertIs<IllegalArgumentException>(
            runCatching {
                EscalationExecutorRegistry(
                    mapOf(
                        EscalationLevel.L0_RETRY to EscalationLevelExecutor {
                            EscalationExecutionResult.Succeeded("retry")
                        }
                    )
                )
            }.exceptionOrNull()
        )
    }

    private fun trigger() = EscalationTrigger(
        nodeId = HealthNodeId("worker-main"),
        scope = HealthScope.WORKER,
        category = HealthFailureCategory.WORKER,
        recoverable = true,
        consecutiveFailures = 1,
        retryBudgetRemaining = false,
        observedAt = at,
        evidenceRefs = setOf("health-observation"),
    )

    private fun registry(
        handler: suspend (EscalationExecutionRequest) -> EscalationExecutionResult,
    ): EscalationExecutorRegistry = EscalationExecutorRegistry(
        EscalationLevel.entries.associateWith {
            EscalationLevelExecutor { request -> handler(request) }
        }
    )

    private class MemoryRepository : EscalationRepository {
        private val records = mutableListOf<EscalationRecord>()

        override suspend fun loadReport(): EscalationRepositoryLoadReport =
            EscalationRepositoryLoadReport(records.toList())

        override suspend fun append(expectedRevision: Long, record: EscalationRecord): Boolean {
            val current = records.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            records += record
            return true
        }
    }
}
