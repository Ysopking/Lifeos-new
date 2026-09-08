package app.lifeos.core.field

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FieldPhysicsFingerprintTest {
    private val domain = StableFieldIds.domain("physics.fingerprint.test")
    private val context = FieldContext(
        temporal = TemporalContext(Instant.parse("2026-09-08T12:00:00Z")),
        domain = DomainContext(domain),
    )

    @Test
    fun `convergence config changes run identity`() {
        val request = request()
        val baseline = FieldConvergenceEngine(
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request)
        val changed = FieldConvergenceEngine(
            config = ConvergenceConfig(maxIterations = 5, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request)

        assertNotEquals(baseline.state.runId, changed.state.runId)
        assertNotEquals(baseline.trace.inputFingerprint, changed.trace.inputFingerprint)
    }

    @Test
    fun `force calculator weights change run identity`() {
        val request = request()
        val baseline = FieldConvergenceEngine(
            forceCalculator = FieldForceCalculator(FieldWeights()),
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request)
        val changed = FieldConvergenceEngine(
            forceCalculator = FieldForceCalculator(FieldWeights(confidence = 0.45, reliability = 0.05)),
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request)

        assertNotEquals(baseline.state.runId, changed.state.runId)
        assertNotEquals(baseline.trace.inputFingerprint, changed.trace.inputFingerprint)
    }

    @Test
    fun `identical physics remains deterministic`() {
        val request = request()
        val first = engine().converge(request)
        val second = engine().converge(request)

        assertEquals(first.state.runId, second.state.runId)
        assertEquals(first.trace.fingerprint(), second.trace.fingerprint())
        assertEquals(first.snapshot.id, second.snapshot.id)
    }

    private fun engine() = FieldConvergenceEngine(
        config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
    )

    private fun request(): FieldConvergenceRequest {
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.HYPOTHESIS,
            semanticKey = "candidate",
            semanticMass = 1.25,
            baseEnergy = 0.8,
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "candidate",
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            explanation = "Fingerprint candidate",
        )
        return FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domain, listOf(node)),
            evidence = emptyList(),
            hypotheses = listOf(hypothesis),
            context = context,
        )
    }
}
