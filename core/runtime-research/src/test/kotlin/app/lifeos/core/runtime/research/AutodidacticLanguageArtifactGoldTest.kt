package app.lifeos.core.runtime.research

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutodidacticLanguageArtifactGoldTest {
    @Test
    fun semantic_state_survives_process_epoch_change_without_gaining_authority() {
        val before = checkpoint("process-a")
        val after = checkpoint("process-b")

        val evidence = AutodidacticLanguageArtifactGoldEvidence.create(before, after)

        assertTrue(AutodidacticLanguageArtifactGoldVerifier().verify(evidence))
        assertNotEquals(
            before.processCheckpointFingerprint,
            after.processCheckpointFingerprint,
        )
        assertTrue(before.semanticFingerprint == after.semanticFingerprint)
        assertFalse(evidence.truthAuthority)
        assertFalse(evidence.factualAuthority)
        assertFalse(evidence.preferenceAuthority)
        assertFalse(evidence.rewriteAuthority)
        assertFalse(evidence.finalizationAuthority)
        assertFalse(evidence.publicationAuthority)
        assertFalse(evidence.executionAuthority)
    }

    @Test
    fun exact_reuse_binding_set_is_part_of_semantic_identity() {
        val first = checkpoint(
            processEpoch = "process-a",
            bindings = listOf("a".repeat(64)),
        )
        val second = checkpoint(
            processEpoch = "process-a",
            bindings = listOf("b".repeat(64)),
        )

        assertNotEquals(first.semanticFingerprint, second.semanticFingerprint)
    }

    @Test
    fun exact_materialized_asset_is_part_of_semantic_identity() {
        val first = checkpoint(
            processEpoch = "process-a",
            assetSha = "6".repeat(64),
        )
        val second = checkpoint(
            processEpoch = "process-a",
            assetSha = "7".repeat(64),
        )

        assertNotEquals(first.semanticFingerprint, second.semanticFingerprint)
    }

    private fun checkpoint(
        processEpoch: String,
        bindings: List<String> = listOf("a".repeat(64), "b".repeat(64)),
        assetSha: String = "6".repeat(64),
    ): AutodidacticLanguageArtifactSemanticCheckpoint =
        AutodidacticLanguageArtifactSemanticCheckpoint.fromSignals(
            languageEpisodeFingerprint = "1".repeat(64),
            ownerStyleProfileFingerprint = "2".repeat(64),
            editLearningEvidenceFingerprint = "3".repeat(64),
            editedStyleSampleFingerprint = "4".repeat(64),
            targetSemanticPlanFingerprint = "5".repeat(64),
            materializedAssetSha256 = assetSha,
            materializedAssetMediaType =
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            livingRefreshCandidateFingerprint = "7".repeat(64),
            crossArtifactReusePlanFingerprint = "8".repeat(64),
            reusableClaimBindingFingerprints = bindings,
            processEpoch = processEpoch,
        )
}
