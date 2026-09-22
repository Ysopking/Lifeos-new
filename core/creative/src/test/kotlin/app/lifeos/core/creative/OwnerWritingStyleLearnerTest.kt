package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OwnerWritingStyleLearnerTest {
    @Test
    fun two_independent_owner_confirmed_samples_learn_descriptive_profile() {
        val first = sample(
            seed = "a",
            text = "Short sentence. Another short sentence.",
            origin = OwnerWritingSampleOrigin.OWNER_AUTHORED,
        )
        val second = sample(
            seed = "b",
            text = "A somewhat longer owner sentence with several words. Another sentence follows.",
            origin = OwnerWritingSampleOrigin.OWNER_APPROVED,
        )

        val result = OwnerWritingStyleLearner().learn("en", listOf(first, second))
        val learned = assertIs<OwnerWritingStyleLearningResult.Learned>(result)
        val profile = learned.profile

        assertEquals(2, profile.sampleFingerprints.size)
        assertEquals(2, profile.sourceArtifactFingerprints.size)
        assertTrue(profile.medianWordsPerSentenceMicros > 0L)
        assertTrue(profile.medianCharsPerParagraphMicros > 0L)
        assertFalse(profile.preferenceAuthority)
        assertFalse(profile.factualAuthority)
        assertFalse(profile.rewriteAuthority)
        assertFalse(profile.finalizationAuthority)
    }

    @Test
    fun repeated_revisions_of_one_artifact_are_not_independent_style_evidence() {
        val first = sample("one-a", "First owner text.", source = "same-source")
        val second = sample("one-b", "Second revision text.", source = "same-source")

        val result = OwnerWritingStyleLearner().learn("en", listOf(first, second))
        val insufficient =
            assertIs<OwnerWritingStyleLearningResult.InsufficientEvidence>(result)

        assertEquals(1, insufficient.distinctSourceArtifactCount)
        assertEquals(2, insufficient.requiredDistinctSourceArtifactCount)
    }

    @Test
    fun raw_text_is_reduced_to_fingerprints_and_metrics() {
        val sample = sample(
            "privacy",
            "Paragraph one has words.\n\nParagraph two is also present.",
        )

        assertEquals(2, sample.paragraphCount)
        assertTrue(sample.sentenceCount >= 2)
        assertTrue(sample.textFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun language_profiles_are_not_mixed() {
        val en = sample("en", "English sample.")
        val de = OwnerWritingStyleSample.fromText(
            languageTag = "de",
            sourceArtifactFingerprint = fp("artifact-de"),
            sourceRevisionFingerprint = fp("revision-de"),
            ownerConfirmationFingerprint = fp("confirmation-de"),
            origin = OwnerWritingSampleOrigin.OWNER_AUTHORED,
            text = "Deutscher Beispieltext.",
        )

        assertFailsWith<IllegalArgumentException> {
            OwnerWritingStyleLearner().learn("en", listOf(en, de))
        }
    }

    @Test
    fun learning_is_deterministic_under_input_order() {
        val first = sample("d1", "One sentence. Two sentence.")
        val second = sample("d2", "Three words here. More words are here.")

        val learner = OwnerWritingStyleLearner()
        assertEquals(
            learner.learn("en", listOf(first, second)),
            learner.learn("en", listOf(second, first)),
        )
    }

    private fun sample(
        seed: String,
        text: String,
        origin: OwnerWritingSampleOrigin = OwnerWritingSampleOrigin.OWNER_AUTHORED,
        source: String = "source-$seed",
    ): OwnerWritingStyleSample =
        OwnerWritingStyleSample.fromText(
            languageTag = "en",
            sourceArtifactFingerprint = fp(source),
            sourceRevisionFingerprint = fp("revision-$seed"),
            ownerConfirmationFingerprint = fp("confirmation-$seed"),
            origin = origin,
            text = text,
        )

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b441-test/v1", value)
}
