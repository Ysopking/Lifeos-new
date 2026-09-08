package app.lifeos.core.field

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FieldSnapshotRegistryTest {
    private val domain = StableFieldIds.domain("snapshot.registry.test")

    @Test
    fun `registry stores verifies and rehydrates deterministic snapshots`() {
        val result = FieldConvergenceEngine(
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request())
        val registry = InMemoryFieldSnapshotRegistry()

        val id = registry.put(result.snapshot)
        val stored = assertNotNull(registry.get(id))
        val restored = stored.rehydrate()

        assertEquals(1, registry.size())
        assertEquals(result.snapshot, stored)
        assertEquals(result.state, restored.state)
        assertEquals(result.status, restored.status)
        assertEquals(result.trace.inputFingerprint, restored.inputFingerprint)
        assertEquals(result.snapshot, registry.latest(domain))
        assertEquals(listOf(result.snapshot), registry.snapshots(domain))
    }

    @Test
    fun `put is idempotent for identical content addressed snapshot`() {
        val snapshot = FieldConvergenceEngine(
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request()).snapshot
        val registry = InMemoryFieldSnapshotRegistry()

        registry.put(snapshot)
        registry.put(snapshot)

        assertEquals(1, registry.size())
        assertEquals(snapshot, registry.get(snapshot.id))
    }

    private fun request(): FieldConvergenceRequest {
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.HYPOTHESIS,
            semanticKey = "registry-candidate",
            baseEnergy = 1.0,
        )
        return FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domain, listOf(node)),
            evidence = emptyList(),
            hypotheses = listOf(
                FieldHypothesis.create(
                    domainId = domain,
                    semanticKey = "registry-candidate",
                    scope = HypothesisScope.DOMAIN,
                    nodeIds = setOf(node.id),
                    explanation = "Registry candidate",
                ),
            ),
            context = FieldContext(
                temporal = TemporalContext(Instant.parse("2026-09-08T12:00:00Z")),
                domain = DomainContext(domain),
            ),
        )
    }
}
