package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class AutomaticSelfHealingPlanRegistryTest {
    @Test
    fun `matching registered plan resolves with stable incident family`() = runTest {
        val registry = AutomaticSelfHealingPlanRegistry(listOf(binding("runtime")))
        val node = HealthNode(HealthNodeId("runtime"), HealthScope.RUNTIME)
        val first = requireNotNull(registry.resolve(node, observation("boom", NOW)))
        val replay = requireNotNull(registry.resolve(node, observation("boom", NOW.plusSeconds(30))))
        assertEquals(first.familyFingerprint, replay.familyFingerprint)
        assertEquals("runtime", first.bindingId)
    }

    @Test
    fun `different failure message creates another incident family`() = runTest {
        val registry = AutomaticSelfHealingPlanRegistry(listOf(binding("runtime")))
        val node = HealthNode(HealthNodeId("runtime"), HealthScope.RUNTIME)
        val first = requireNotNull(registry.resolve(node, observation("boom-a", NOW)))
        val second = requireNotNull(registry.resolve(node, observation("boom-b", NOW.plusSeconds(1))))
        assertNotEquals(first.familyFingerprint, second.familyFingerprint)
    }

    @Test
    fun `ambiguous automatic repair bindings fail closed`() = runTest {
        val registry = AutomaticSelfHealingPlanRegistry(
            listOf(binding("a"), binding("b")),
        )
        assertFailsWith<IllegalArgumentException> {
            registry.resolve(
                HealthNode(HealthNodeId("runtime"), HealthScope.RUNTIME),
                observation("boom", NOW),
            )
        }
    }

    private fun binding(id: String) = AutomaticSelfHealingPlanBinding(
        id = id,
        matches = { node, _ -> node.scope == HealthScope.RUNTIME },
        planFactory = { node, _ ->
            RecoveryPlan(
                nodeId = node.id,
                source = "test",
                actions = listOf(object : RecoveryAction {
                    override val id = "repair"
                    override suspend fun execute() = RecoveryActionResult.Success()
                }),
                verificationProbes = listOf(
                    RuntimeRepairProbe("probe", node.id) {
                        RepairProbeObservation(RepairProbeStatus.HEALTHY)
                    }
                ),
            )
        },
        resources = SelfHealingResourceProfile(
            hardQuota = ResourceBudgetQuota(1000, 2, 1024, 1024, 0, 1),
            perActionRequested = ResourceBudgetUsage(500, 1, 512, 512, 0, 1),
        ),
    )

    private fun observation(message: String, at: Instant) = HealthObservation(
        nodeId = HealthNodeId("runtime"),
        state = HealthState.UNHEALTHY,
        observedAt = at,
        source = "runtime",
        message = message,
        classification = FailureClassification(
            category = HealthFailureCategory.RECOVERY,
            scope = HealthScope.RUNTIME,
            recoverable = true,
            suggestedState = HealthState.UNHEALTHY,
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T13:20:00Z")
    }
}
