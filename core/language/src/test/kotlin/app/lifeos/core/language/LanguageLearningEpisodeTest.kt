package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageLearningEpisodeTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun interpretation_reference_is_deterministic_without_storing_raw_utterance() {
        val result = engine.understand("Suche bitte nach dem aktuellen Projektstatus.")
        val first = LanguageLearningInterpretationRef.from(result)
        val second = LanguageLearningInterpretationRef.from(result)

        assertEquals(first, second)
        assertTrue(first.utteranceFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.objectiveFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun verified_action_outcome_becomes_learning_eligible_but_non_authoritative() {
        val interpretation = LanguageLearningInterpretationRef.from(
            engine.understand("Erstelle einen Bericht aus den Daten.")
        )
        val episode = LanguageLearningEpisode.create(
            interpretation = interpretation,
            actionFingerprint = "a".repeat(64),
            outcomeFingerprint = "b".repeat(64),
            status = LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
            sourceCycleId = "cycle-1",
        )

        assertTrue(episode.learningEligible)
        assertFalse(episode.truthAuthority)
        assertFalse(episode.grammarPromotionAuthority)
        assertFalse(episode.lexicalPromotionAuthority)
        assertFalse(episode.executionAuthority)
    }

    @Test
    fun explicit_owner_correction_binds_exact_replacement_interpretation() {
        val interpretation = LanguageLearningInterpretationRef.from(
            engine.understand("Mach das nochmal.")
        )
        val feedback = LanguageOwnerFeedback.create(
            kind = LanguageLearningFeedbackKind.OWNER_CORRECTION,
            sourceFingerprint = "c".repeat(64),
            replacementInterpretationFingerprint = "d".repeat(64),
        )
        val episode = LanguageLearningEpisode.create(
            interpretation = interpretation,
            ownerFeedback = feedback,
            status = LanguageLearningEpisodeStatus.OWNER_CORRECTED,
            sourceCycleId = "cycle-2",
        )

        assertTrue(episode.learningEligible)
        assertEquals(
            "d".repeat(64),
            episode.ownerFeedback?.replacementInterpretationFingerprint,
        )
    }

    @Test
    fun unconfirmed_or_incomplete_feedback_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                sourceFingerprint = "e".repeat(64),
                ownerConfirmed = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_CORRECTION,
                sourceFingerprint = "e".repeat(64),
            )
        }
    }

    @Test
    fun verified_outcome_requires_both_action_and_outcome_fingerprints() {
        val interpretation = LanguageLearningInterpretationRef.from(
            engine.understand("Speichere das.")
        )
        assertFailsWith<IllegalArgumentException> {
            LanguageLearningEpisode.create(
                interpretation = interpretation,
                actionFingerprint = "f".repeat(64),
                outcomeFingerprint = null,
                status = LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
                sourceCycleId = "cycle-3",
            )
        }
    }
}
