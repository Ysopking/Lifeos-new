package app.lifeos.core.field

import app.lifeos.core.model.WorldStateSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldCouplingKernelTest {
    private val source = state(mass = 4.0, coherence = 0.9, coupling = 0.8)
    private val target = state(mass = 2.0, coherence = 0.7, coupling = 0.6)

    @Test
    fun `support is attractive and contradiction is repulsive`() {
        val kernel = WorldCouplingKernel(temporalDecay = 0.2)
        val support = kernel.couple(source, target, relationStrength = 0.8, interactionPolarity = 1.0)
        val contradiction = kernel.couple(source, target, relationStrength = 0.8, interactionPolarity = -1.0)

        assertTrue(support.signedCoupling > 0.0)
        assertTrue(contradiction.signedCoupling < 0.0)
        assertEquals(support.magnitude, contradiction.magnitude, absoluteTolerance = 1e-12)
    }

    @Test
    fun `temporal distance weakens coupling`() {
        val kernel = WorldCouplingKernel(temporalDecay = 1.0)
        val near = kernel.couple(source, target, 1.0, 1.0, temporalDistance = 0.0)
        val far = kernel.couple(source, target, 1.0, 1.0, temporalDistance = 10.0)

        assertTrue(near.magnitude > far.magnitude)
    }

    @Test
    fun `running scale changes coupling without changing state`() {
        val kernel = WorldCouplingKernel()
        val low = kernel.couple(source, target, 0.5, 1.0, scale = WorldCouplingScale(mu = 1.0, runningCoupling = 0.5))
        val high = kernel.couple(source, target, 0.5, 1.0, scale = WorldCouplingScale(mu = 1.0, runningCoupling = 1.0))

        assertTrue(high.magnitude > low.magnitude)
        assertEquals(source, source.copy())
        assertEquals(target, target.copy())
    }

    @Test
    fun `fingerprints are deterministic`() {
        val kernel = WorldCouplingKernel(0.3)
        assertEquals(kernel.fingerprint(), WorldCouplingKernel(0.3).fingerprint())
        assertEquals(source.worldFingerprint(), source.copy().worldFingerprint())
    }

    private fun state(mass: Double, coherence: Double, coupling: Double) = WorldStateSignature(
        semanticMass = mass,
        energy = 1.0,
        phase = 0.25,
        polarity = 0.0,
        entropy = 1.0 - coherence,
        coherence = coherence,
        coupling = coupling,
        temporalDepth = 0.0,
        potential = 1.0,
    )
}
