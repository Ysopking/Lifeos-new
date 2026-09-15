package app.lifeos.core.field

import app.lifeos.core.model.WorldStateSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldInvariantRegistryTest {
    @Test
    fun `structural registry accepts a valid state`() {
        val report = WorldInvariantRegistry.structural().evaluate(previous = null, current = state())
        assertFalse(report.hasBlockingViolation)
        assertTrue(report.violations.isEmpty())
    }

    @Test
    fun `custom invariant violations are deterministic`() {
        val first = object : WorldInvariant {
            override val id = "test.a"
            override val severity = WorldInvariantSeverity.WARNING
            override fun evaluate(transition: WorldTransition): String? = "warning"
        }
        val second = object : WorldInvariant {
            override val id = "test.b"
            override val severity = WorldInvariantSeverity.BLOCKING
            override fun evaluate(transition: WorldTransition): String? = "blocked"
        }
        val registry = WorldInvariantRegistry(listOf(second, first))
        val report = registry.evaluate(null, state())

        assertEquals(listOf("test.a", "test.b"), report.violations.map { it.invariantId })
        assertTrue(report.hasBlockingViolation)
        assertEquals(registry.fingerprint(), WorldInvariantRegistry(listOf(first, second)).fingerprint())
    }

    private fun state() = WorldStateSignature(
        semanticMass = 1.0,
        energy = 1.0,
        phase = 0.5,
        polarity = 0.0,
        entropy = 0.2,
        coherence = 0.8,
        coupling = 0.7,
        temporalDepth = 1.0,
        potential = 0.5,
    )
}
