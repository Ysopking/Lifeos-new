package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class EscalationRuntimeRegistryTest {
    @Test
    fun registryDelegatesToSingleInstalledCoordinator() = runBlocking {
        val coordinator = EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(MemoryRepository()) { NOW },
            executors = EscalationExecutorRegistry(
                EscalationLevel.entries.associateWith { level ->
                    EscalationLevelExecutor {
                        EscalationExecutionResult.Succeeded("executed:" + level.name)
                    }
                }
            ),
        )
        EscalationRuntimeRegistry.install(coordinator)
        assertSame(coordinator, EscalationRuntimeRegistry.requireCurrent())

        val trigger = EscalationTrigger(
            nodeId = HealthNodeId("registry-test"),
            scope = HealthScope.RUNTIME,
            category = HealthFailureCategory.TRANSIENT,
            recoverable = true,
            consecutiveFailures = 1,
            retryBudgetRemaining = true,
            observedAt = NOW,
        )
        EscalationRuntimeRegistry.coordinate(trigger)
        assertSame(coordinator, EscalationRuntimeRegistry.currentOrNull())
    }

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

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T02:30:00Z")
    }
}
