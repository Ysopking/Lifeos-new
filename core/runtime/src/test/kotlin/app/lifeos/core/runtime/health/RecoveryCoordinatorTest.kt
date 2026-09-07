package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RecoveryCoordinatorTest {
    private val nodeId = HealthNodeId("worker:test")
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun fallsThroughRetryableFailureAndMarksHealthyOnRecovery() = runTest {
        val health = HealthGraph(now = { t0 })
        val quarantine = QuarantineRegistry()
        health.register(nodeId, HealthScope.WORKER)
        val coordinator = RecoveryCoordinator(health, quarantine, now = { t0 })

        val result = coordinator.recover(
            RecoveryPlan(
                nodeId = nodeId,
                source = "test",
                actions = listOf(
                    action("restart", RecoveryActionResult.Failure("restart-failed")),
                    action("rebuild", RecoveryActionResult.Success("rebuilt")),
                ),
            )
        )

        val recovered = assertIs<RecoveryResult.Recovered>(result)
        assertEquals("rebuild", recovered.actionId)
        assertEquals(HealthState.HEALTHY, health.node(nodeId)?.state)
    }

    @Test
    fun exhaustedRecoveryQuarantinesNodeWithoutDeletingHealthHistory() = runTest {
        val health = HealthGraph(now = { t0 })
        val quarantine = QuarantineRegistry()
        health.register(nodeId, HealthScope.WORKER)
        val coordinator = RecoveryCoordinator(health, quarantine, now = { t0 })

        val result = coordinator.recover(
            RecoveryPlan(
                nodeId = nodeId,
                source = "test",
                actions = listOf(
                    action("restart", RecoveryActionResult.Failure("still-broken", retryable = false)),
                ),
            )
        )

        val exhausted = assertIs<RecoveryResult.Exhausted>(result)
        assertEquals(true, exhausted.quarantined)
        assertEquals(HealthState.QUARANTINED, health.node(nodeId)?.state)
        assertNotNull(quarantine.active(nodeId, t0))
        assertEquals(2, health.recent(nodeId).count { it.state != HealthState.QUARANTINED })
    }

    private fun action(actionId: String, result: RecoveryActionResult) = object : RecoveryAction {
        override val id: String = actionId
        override suspend fun execute(): RecoveryActionResult = result
    }
}
