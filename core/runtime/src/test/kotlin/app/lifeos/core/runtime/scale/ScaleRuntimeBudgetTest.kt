package app.lifeos.core.runtime.scale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleRuntimeBudgetTest {
    @Test
    fun ciCorpusExceedsDurableAndPresentationWorkingSetsWithoutChangingTheirBounds() {
        val corpus = DeterministicScaleCorpus(
            ScaleCorpusConfig.forTier(ScaleFixtureTier.CI)
        )
        val evidence = corpus.runtimeEvidence()

        assertEquals(10_000, evidence.durablePhotonCount)
        assertEquals(
            RuntimeRetentionBudgets.MAX_BOOTSTRAP_RETAINED_PHOTONS,
            evidence.retainedPhotonBudget,
        )
        assertEquals(5_904, evidence.minimumColdPhotonCount)
        assertEquals(20_512, evidence.uiItemCount)
        assertEquals(
            RuntimeRetentionBudgets.MAX_PRESENTATION_VISIBLE_ITEMS,
            evidence.presentationVisibleBudget,
        )
        assertTrue(evidence.presentationTruncated)
    }
}
