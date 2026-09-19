package app.lifeos.core.runtime.scale

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleGoldEvidenceTest {
    @Test
    fun sealDeterministicCiScaleEvidenceWhenRequested() {
        val corpus = DeterministicScaleCorpus(ScaleCorpusConfig.forTier(ScaleFixtureTier.CI))
        val descriptor = corpus.descriptor()
        val runtime = corpus.runtimeEvidence()

        assertEquals(10_000, descriptor.photonCount)
        assertEquals(25_000, descriptor.relationshipCount)
        assertEquals(10_000, descriptor.traceNodeCount)
        assertEquals(10_000, descriptor.ledgerEventCount)
        assertTrue(descriptor.uiItemCount > RuntimeRetentionBudgets.MAX_PRESENTATION_VISIBLE_ITEMS)
        assertEquals(RuntimeRetentionBudgets.MAX_BOOTSTRAP_RETAINED_PHOTONS, runtime.retainedPhotonBudget)

        val targetDirectory = System.getenv("LIFEOS_SCALE_EVIDENCE_DIR")?.takeIf { it.isNotBlank() } ?: return
        val directory = File(targetDirectory)
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "scale-runtime.txt").writeText(
            listOf(
                "contract=lifeos-m216-scale/v1",
                "corpus_fingerprint=" + descriptor.fingerprint,
                "photon_count=" + descriptor.photonCount,
                "photon_revision_count=" + descriptor.photonRevisionCount,
                "relationship_count=" + descriptor.relationshipCount,
                "trace_node_count=" + descriptor.traceNodeCount,
                "trace_count=" + descriptor.traceCount,
                "ledger_event_count=" + descriptor.ledgerEventCount,
                "ledger_key_count=" + descriptor.ledgerKeyCount,
                "ui_item_count=" + descriptor.uiItemCount,
                "bootstrap_retained_budget=" + runtime.retainedPhotonBudget,
                "minimum_cold_photon_count=" + runtime.minimumColdPhotonCount,
                "presentation_visible_budget=" + runtime.presentationVisibleBudget,
                "presentation_truncated=" + runtime.presentationTruncated,
            ).joinToString(separator = "\n", postfix = "\n")
        )
    }
}
