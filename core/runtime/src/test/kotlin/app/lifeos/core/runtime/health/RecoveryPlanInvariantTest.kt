package app.lifeos.core.runtime.health

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RecoveryPlanInvariantTest {
    @Test
    fun recoveryPlanRequiresVerificationProbe() {
        assertFailsWith<IllegalArgumentException> {
            RecoveryPlan(
                nodeId = HealthNodeId("worker:test"),
                source = "test",
                actions = listOf(
                    object : RecoveryAction {
                        override val id: String = "restart"
                        override suspend fun execute(): RecoveryActionResult = RecoveryActionResult.Success()
                    }
                ),
                verificationProbes = emptyList(),
            )
        }
    }
}
