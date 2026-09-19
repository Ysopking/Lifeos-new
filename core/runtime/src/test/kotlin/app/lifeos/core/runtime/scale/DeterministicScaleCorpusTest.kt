package app.lifeos.core.runtime.scale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeterministicScaleCorpusTest {
    @Test
    fun ciTierMeetsM216CardinalityContractWithoutFullListRetention() {
        val config = ScaleCorpusConfig.forTier(ScaleFixtureTier.CI)
        val corpus = DeterministicScaleCorpus(config)
        val descriptor = corpus.descriptor()

        assertEquals(10_000, descriptor.photonCount)
        assertEquals(30_000L, descriptor.photonRevisionCount)
        assertEquals(25_000, descriptor.relationshipCount)
        assertEquals(10_000, descriptor.traceNodeCount)
        assertEquals(10_000, descriptor.ledgerEventCount)
        assertTrue(descriptor.uiItemCount > 20_000)
        assertEquals(64, descriptor.fingerprint.length)

        assertEquals(config.photonCount, corpus.photonIds().count())
        assertEquals(config.relationshipCount, corpus.relationshipEdges().count())
        assertEquals(config.traceNodeCount, corpus.traceNodes().count())
        assertEquals(config.ledgerEventCount, corpus.ledgerEvents().count())
        assertEquals(config.uiItemCount, corpus.uiItemIds().count())
    }

    @Test
    fun sameSeedIsReplayStableAndChangedSeedChangesCorpusIdentity() {
        val config = ScaleCorpusConfig.forTier(ScaleFixtureTier.CI)
        val first = DeterministicScaleCorpus(config).descriptor()
        val second = DeterministicScaleCorpus(config).descriptor()
        val changed = DeterministicScaleCorpus(config.copy(seed = config.seed + "-changed")).descriptor()

        assertEquals(first, second)
        assertNotEquals(first.fingerprint, changed.fingerprint)
    }

    @Test
    fun globalAndPerKeyLedgerRevisionsRemainDeterministic() {
        val config = ScaleCorpusConfig.forTier(ScaleFixtureTier.CI)
        val events = DeterministicScaleCorpus(config).ledgerEvents().take(130).toList()

        assertEquals((1L..130L).toList(), events.map { it.globalRevision })
        val firstKey = events.first().key
        assertEquals(listOf(1L, 2L, 3L), events.filter { it.key == firstKey }.map { it.perKeyRevision })
    }
}
