package app.lifeos.next

import kotlin.test.Test
import kotlin.test.assertEquals

class LifeOsStartupCompositionTest {
    @Test
    fun `startup installs recovery registries before kernel execution is exposed`() {
        val order = mutableListOf<String>()

        LifeOsStartupComposition.start(
            LifeOsStartupHooks(
                installSharedResourceRuntime = { order += "shared-resources" },
                installGoalExecutionRuntime = { order += "goal-execution" },
                createKernel = { order += "kernel-created" },
                installDeepSearchRuntime = { order += "deepsearch" },
                startSelfHealingRuntime = { order += "self-healing" },
                installDurableGoalPlanRuntime = { order += "goal-plan" },
                startKernel = { order += "kernel-start" },
            )
        )

        assertEquals(
            listOf(
                "shared-resources",
                "goal-execution",
                "kernel-created",
                "deepsearch",
                "self-healing",
                "goal-plan",
                "kernel-start",
            ),
            order,
        )
    }
}
