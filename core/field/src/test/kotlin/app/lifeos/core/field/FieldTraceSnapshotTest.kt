package app.lifeos.core.field

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FieldTraceSnapshotTest {
    private val domain = StableFieldIds.domain("trace.snapshot.test")
    private val context = FieldContext(
        temporal = TemporalContext(Instant.parse("2026-09-08T12:00:00Z")),
        domain = DomainContext(domain),
    )

    @Test
    fun `convergence emits complete trace and round trippable snapshot`() {
        val result = engine().converge(request())

        assertEquals(result.iterations, result.trace.iterations.size)
        assertEquals(result.state.energy.fingerprint(), result.trace.finalEnergyFingerprint)
        assertEquals(result.trace.fingerprint(), result.snapshot.traceFingerprint)
        assertEquals(result.state.energy, result.snapshot.state.energy)
        assertEquals(result.snapshot, FieldSnapshotCodec.decode(FieldSnapshotCodec.encode(result.snapshot)))
    }

    @Test
    fun `field version changes run and snapshot identity`() {
        val v1 = engine(DomainFieldRegistry(listOf(field(version = 1)))).converge(request())
        val v2 = engine(DomainFieldRegistry(listOf(field(version = 2)))).converge(request())

        assertNotEquals(v1.trace.fieldSetFingerprint, v2.trace.fieldSetFingerprint)
        assertNotEquals(v1.state.runId, v2.state.runId)
        assertNotEquals(v1.snapshot.id, v2.snapshot.id)
    }

    private fun engine(registry: DomainFieldRegistry = DomainFieldRegistry.EMPTY) = FieldConvergenceEngine(
        config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        registry = registry,
    )

    private fun request(): FieldConvergenceRequest {
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.HYPOTHESIS,
            semanticKey = "candidate",
            baseEnergy = 1.0,
        )
        return FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domain, listOf(node)),
            evidence = emptyList(),
            hypotheses = listOf(
                FieldHypothesis.create(
                    domainId = domain,
                    semanticKey = "candidate",
                    scope = HypothesisScope.DOMAIN,
                    nodeIds = setOf(node.id),
                    explanation = "Traceable candidate",
                ),
            ),
            context = context,
        )
    }

    private fun field(version: Int): DomainField = object : DomainField {
        override val descriptor = DomainFieldDescriptor(
            domainId = domain,
            name = "trace-field",
            version = version,
        )

        override fun seed(evidence: List<FieldEvidence>, context: FieldContext): DomainFieldSeed =
            error("Field seeding is not used by this convergence path")
    }
}
