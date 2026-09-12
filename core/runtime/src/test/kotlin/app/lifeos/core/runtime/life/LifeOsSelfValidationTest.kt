package app.lifeos.core.runtime.life

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsSelfValidationTest {
    @Test
    fun integratedSuitePassesAllEightBlocksAndChaosProbes() = runTest {
        val (readiness, chaos) = LifeOsSelfValidation(LifeOsIntegratedCognitionSuite()).validate()
        assertEquals(8, readiness.blocks.size)
        assertTrue(readiness.complete)
        assertTrue(chaos.passed)
        assertEquals(ChaosScenario.entries.size, chaos.probes.size)
    }
}
