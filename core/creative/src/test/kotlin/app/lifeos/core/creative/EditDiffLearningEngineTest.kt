package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditDiffLearningEngineTest {
    @Test
    fun owner_edit_becomes_bounded_diff_evidence_and_b441_sample() {
        val result = EditDiffLearningEngine().analyze(
            languageTag = "en",
            sourceArtifactFingerprint = fp("artifact"),
            beforeRevisionFingerprint = fp("before-revision"),
            afterRevisionFingerprint = fp("after-revision"),
            ownerConfirmationFingerprint = fp("owner-confirmation"),
            beforeText = "This is a very long sentence with many unnecessary words. Another sentence remains.",
            afterText = "Short sentence. Another remains.",
        )
        val learned = assertIs<EditDiffLearningResult.Learned>(result)
        val evidence = learned.evidence

        assertTrue(
            evidence.signals.any {
                it.kind == OwnerEditDiffSignalKind.SURFACE_CHANGED
            }
        )
        assertTrue(
            evidence.signals.any {
                it.kind == OwnerEditDiffSignalKind.TOTAL_TEXT_SHORTER
            }
        )
        assertEquals(OwnerWritingSampleOrigin.OWNER_APPROVED, evidence.afterStyleSample.origin)
        assertEquals(evidence.sourceArtifactFingerprint, evidence.afterStyleSample.sourceArtifactFingerprint)
        assertEquals(evidence.afterRevisionFingerprint, evidence.afterStyleSample.sourceRevisionFingerprint)
        assertFalse(evidence.globalStyleRuleAuthority)
        assertFalse(evidence.preferenceAuthority)
        assertFalse(evidence.rewriteAuthority)
        assertFalse(evidence.finalizationAuthority)
    }

    @Test
    fun one_edit_does_not_become_global_owner_style() {
        val learned = assertIs<EditDiffLearningResult.Learned>(
            EditDiffLearningEngine().analyze(
                languageTag = "en",
                sourceArtifactFingerprint = fp("one-artifact"),
                beforeRevisionFingerprint = fp("one-before"),
                afterRevisionFingerprint = fp("one-after"),
                ownerConfirmationFingerprint = fp("one-confirmation"),
                beforeText = "Original text.",
                afterText = "Edited owner text.",
            )
        )

        val profileResult = OwnerWritingStyleLearner().learn(
            "en",
            listOf(learned.evidence.afterStyleSample),
        )
        assertIs<OwnerWritingStyleLearningResult.InsufficientEvidence>(profileResult)
    }

    @Test
    fun unchanged_surface_returns_no_change() {
        val result = EditDiffLearningEngine().analyze(
            languageTag = "en",
            sourceArtifactFingerprint = fp("same-artifact"),
            beforeRevisionFingerprint = fp("same-before"),
            afterRevisionFingerprint = fp("same-after"),
            ownerConfirmationFingerprint = fp("same-confirmation"),
            beforeText = "Same text.",
            afterText = "Same text.",
        )

        assertIs<EditDiffLearningResult.NoChange>(result)
    }

    @Test
    fun paragraph_structure_delta_is_observed_without_promotion() {
        val result = EditDiffLearningEngine().analyze(
            languageTag = "en",
            sourceArtifactFingerprint = fp("paragraph-artifact"),
            beforeRevisionFingerprint = fp("paragraph-before"),
            afterRevisionFingerprint = fp("paragraph-after"),
            ownerConfirmationFingerprint = fp("paragraph-confirmation"),
            beforeText = "One paragraph with text.",
            afterText = "First paragraph.\n\nSecond paragraph.",
        )
        val learned = assertIs<EditDiffLearningResult.Learned>(result)

        assertTrue(
            learned.evidence.signals.any {
                it.kind == OwnerEditDiffSignalKind.MORE_PARAGRAPHS
            }
        )
        assertFalse(
            learned.evidence.signals.first().globalStyleRuleAuthority
        )
    }

    @Test
    fun analysis_is_deterministic() {
        val engine = EditDiffLearningEngine()
        val args = {
            engine.analyze(
                languageTag = "en",
                sourceArtifactFingerprint = fp("det-artifact"),
                beforeRevisionFingerprint = fp("det-before"),
                afterRevisionFingerprint = fp("det-after"),
                ownerConfirmationFingerprint = fp("det-confirmation"),
                beforeText = "Longer initial text with more words.",
                afterText = "Short edit.",
            )
        }

        assertEquals(args(), args())
    }

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b442-test/v1", value)
}
