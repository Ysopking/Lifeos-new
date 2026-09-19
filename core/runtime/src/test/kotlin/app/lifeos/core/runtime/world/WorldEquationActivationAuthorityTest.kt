package app.lifeos.core.runtime.world

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldEquationActivationAuthorityTest {
    @Test
    fun baselineIsSeededThenPromotedPhysicsBecomesActiveByCas() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val registry = InMemoryWorldEquationRegistry(listOf(baseline))
        val heads = MemoryHeadRepository()
        val authority = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
        )

        assertEquals(baseline.version, authority.activeVersion())
        val seeded = requireNotNull(heads.load())
        assertEquals(1L, seeded.revision)
        assertNull(seeded.predecessorEquationVersion)

        val candidate = baseline.copy(version = "lifeos-world-cognitive-v2")
        authority.registerCandidate(candidate)
        val promoted = authority.activate(
            version = candidate.version,
            promotionId = "promotion:test-v2",
        )

        assertEquals(2L, promoted.revision)
        assertEquals(candidate.version, promoted.activeEquationVersion)
        assertEquals(baseline.version, promoted.predecessorEquationVersion)
        assertEquals("promotion:test-v2", promoted.sourcePromotionId)
        assertEquals(candidate.version, authority.activeVersion())
    }

    @Test
    fun rollbackRestoresExactRegisteredPredecessorAfterRehydration() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = baseline.copy(version = "lifeos-world-cognitive-v2")
        val registry = InMemoryWorldEquationRegistry(listOf(baseline, candidate))
        val heads = MemoryHeadRepository()
        val first = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
        )

        assertEquals(baseline.version, first.activeVersion())
        first.activate(candidate.version, "promotion:test-v2")
        assertEquals(candidate.version, first.activeVersion())

        val rehydrated = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
        )
        val restored = rehydrated.rollbackToPredecessor(
            expectedCurrentVersion = candidate.version,
            rollbackDecisionId = "decision:test-rollback",
        )

        assertEquals(3L, restored.revision)
        assertEquals(baseline.version, restored.activeEquationVersion)
        assertEquals(candidate.version, restored.predecessorEquationVersion)
        assertEquals("rollback:decision:test-rollback", restored.sourcePromotionId)
        assertEquals(baseline.version, rehydrated.activeVersion())
    }

    @Test
    fun unregisteredPhysicsCannotBecomeActive() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val authority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = MemoryHeadRepository(),
            baseline = baseline,
        )

        assertFailsWith<IllegalArgumentException> {
            authority.activate(
                version = "missing-v2",
                promotionId = "promotion:missing",
            )
        }
    }

    private class MemoryHeadRepository : WorldEquationHeadRepository {
        private var head: WorldEquationHead? = null

        override suspend fun load(): WorldEquationHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: WorldEquationHead,
        ): Boolean {
            if (head?.revision != expectedRevision) return false
            require(next.revision == (expectedRevision ?: 0L) + 1L)
            require(next.predecessorEquationVersion == head?.activeEquationVersion)
            head = next
            return true
        }

        override suspend fun loadReport(): WorldEquationHeadLoadReport =
            WorldEquationHeadLoadReport(
                head = head,
                corrupted = false,
                message = null,
            )
    }
}
