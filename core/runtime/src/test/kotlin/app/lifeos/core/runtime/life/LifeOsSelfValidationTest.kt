package app.lifeos.core.runtime.life

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsSelfValidationTest {
    @Test
    fun integratedSuitePassesAllRuntimeBlocksAndChaosProbes() = runTest {
        val (readiness, chaos) = LifeOsSelfValidation(LifeOsIntegratedCognitionSuite()).validate()
        assertEquals(LifeOsBlock.entries.size, readiness.blocks.size)
        assertEquals(16, readiness.blocks.size)
        val blocked = readiness.blocks
            .filter { it.state != ReadinessState.READY }
            .joinToString(separator = ", ") { "${it.block}:${it.state}:${it.detail}" }
        assertTrue(readiness.complete, "Runtime readiness blocked: $blocked")
        assertTrue(chaos.passed)
        assertEquals(ChaosScenario.entries.size, chaos.probes.size)
    }
}
