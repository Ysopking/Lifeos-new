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
    fun fallsThroughRetryableFailureAndMarksHealthyOnlyAfterProbeVerification() = runTest {
        val health = HealthGraph(now = { t0 })
        val quarantine = QuarantineRegistry()
        health.register(nodeId, HealthScope.WORKER)
        val coordinator = RecoveryCoordinator(health, quarantine, now = { t0 })
        var probeStatus = RepairProbeStatus.UNHEALTHY

        val result = coordinator.recover(
            RecoveryPlan(
                nodeId = nodeId,
                source = "test",
                actions = listOf(
                    action("restart", RecoveryActionResult.Failure("restart-failed")),
                    action("rebuild") {
                        probeStatus = RepairProbeStatus.HEALTHY
                        RecoveryActionResult.Success("rebuilt")
                    },
                ),
                verificationProbes = listOf(workerProbe { probeStatus }),
            )
        )

        val recovered = assertIs<RecoveryResult.Recovered>(result)
        assertEquals("rebuild", recovered.actionId)
        assertEquals(true, recovered.evidence.verifiedHealthy)
        assertEquals(HealthState.HEALTHY, health.node(nodeId)?.state)
    }

    @Test
    fun successfulActionWithFailedVerificationFallsThroughToNextAction() = runTest {
        val health = HealthGraph(now = { t0 })
        val quarantine = QuarantineRegistry()
        health.register(nodeId, HealthScope.WORKER)
        val coordinator = RecoveryCoordinator(health, quarantine, now = { t0 })
        var probes = 0

        val result = coordinator.recover(
            RecoveryPlan(
                nodeId = nodeId,
                source = "test",
                actions = listOf(
                    action("restart", RecoveryActionResult.Success("restart-returned")),
                    action("rebuild", RecoveryActionResult.Success("rebuild-returned")),
                ),
                verificationProbes = listOf(
                    workerProbe {
                        probes += 1
                        if (probes == 1) RepairProbeStatus.UNHEALTHY else RepairProbeStatus.HEALTHY
                    }
                ),
            )
        )

        val recovered = assertIs<RecoveryResult.Recovered>(result)
        assertEquals("rebuild", recovered.actionId)
        assertEquals(2, probes)
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
                verificationProbes = listOf(workerProbe { RepairProbeStatus.UNHEALTHY }),
            )
        )

        val exhausted = assertIs<RecoveryResult.Exhausted>(result)
        assertEquals(true, exhausted.quarantined)
        assertEquals(HealthState.QUARANTINED, health.node(nodeId)?.state)
        assertNotNull(quarantine.active(nodeId, t0))
        assertEquals(2, health.recent(nodeId).count { it.state != HealthState.QUARANTINED })
    }

    @Test
    fun positiveActionCannotReleaseQuarantineWhenVerificationRemainsUnhealthy() = runTest {
        val health = HealthGraph(now = { t0 })
        val quarantine = QuarantineRegistry()
        health.register(nodeId, HealthScope.WORKER)
        val coordinator = RecoveryCoordinator(health, quarantine, now = { t0 })

        val result = coordinator.recover(
            RecoveryPlan(
                nodeId = nodeId,
                source = "test",
                actions = listOf(action("restart", RecoveryActionResult.Success("returned-success"))),
                verificationProbes = listOf(workerProbe { RepairProbeStatus.UNHEALTHY }),
            )
        )

        val exhausted = assertIs<RecoveryResult.Exhausted>(result)
        assertEquals("verification-failed:worker-readiness:unhealthy", exhausted.lastFailure)
        assertEquals(RepairProbeStatus.UNHEALTHY, exhausted.evidence?.worstStatus)
        assertEquals(HealthState.QUARANTINED, health.node(nodeId)?.state)
        assertNotNull(quarantine.active(nodeId, t0))
    }

    private fun workerProbe(status: suspend () -> RepairProbeStatus): RepairProbe = WorkerRepairProbe(
        id = "worker-readiness",
        nodeId = nodeId,
    ) {
        RepairProbeObservation(status = status())
    }

    private fun action(actionId: String, result: RecoveryActionResult) = action(actionId) { result }

    private fun action(actionId: String, execute: suspend () -> RecoveryActionResult) = object : RecoveryAction {
        override val id: String = actionId
        override suspend fun execute(): RecoveryActionResult = execute()
    }
}
