package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexicalSelfLearningEngineTest {
    @Test
    fun owner_correction_can_create_owner_language_candidate_without_promotion_authority() {
        val episode = episode(LanguageLearningEpisodeStatus.OWNER_CORRECTED, "cycle-a")
        val observation = LexicalLearningObservation.create(
            episode = episode,
            surfaceForm = "Weltformel",
            semanticFingerprint = "a".repeat(64),
            scope = LexicalLearningScope.OWNER_LANGUAGE,
            evidenceKind = LexicalLearningEvidenceKind.OWNER_CORRECTION,
            sourceFingerprint = "b".repeat(64),
        )

        val candidate = LexicalSelfLearningEngine().induce(listOf(observation)).single()

        assertEquals("weltformel", candidate.normalizedForm)
        assertEquals(LexicalLearningScope.OWNER_LANGUAGE, candidate.scope)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.parserMutationAuthority)
        assertFalse(candidate.truthAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun one_general_usage_is_not_enough_but_two_independent_cycles_are() {
        val first = LexicalLearningObservation.create(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-a"),
            "Photon",
            "c".repeat(64),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            LexicalLearningEvidenceKind.VERIFIED_CONTEXTUAL_USAGE,
            "d".repeat(64),
        )
        val second = LexicalLearningObservation.create(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-b"),
            "photon",
            "c".repeat(64),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            LexicalLearningEvidenceKind.VERIFIED_CONTEXTUAL_USAGE,
            "e".repeat(64),
        )
        val engine = LexicalSelfLearningEngine()

        assertTrue(engine.induce(listOf(first)).isEmpty())
        assertEquals(1, engine.induce(listOf(first, second)).size)
    }

    @Test
    fun external_observation_cannot_be_labeled_owner_language() {
        val episode = episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-x")

        assertFailsWith<IllegalArgumentException> {
            LexicalLearningObservation.create(
                episode,
                "foo",
                "f".repeat(64),
                LexicalLearningScope.OWNER_LANGUAGE,
                LexicalLearningEvidenceKind.EXTERNAL_OBSERVATION,
                "1".repeat(64),
            )
        }
    }

    @Test
    fun exact_duplicate_replay_is_idempotent_and_input_order_is_deterministic() {
        val a = LexicalLearningObservation.create(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-a"),
            "Alpha",
            "2".repeat(64),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            LexicalLearningEvidenceKind.VERIFIED_CONTEXTUAL_USAGE,
            "3".repeat(64),
        )
        val b = LexicalLearningObservation.create(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-b"),
            "alpha",
            "2".repeat(64),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            LexicalLearningEvidenceKind.VERIFIED_CONTEXTUAL_USAGE,
            "4".repeat(64),
        )
        val engine = LexicalSelfLearningEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun episode(
        status: LanguageLearningEpisodeStatus,
        cycle: String,
    ): LanguageLearningEpisode {
        val interpretation = interpretation(cycle)
        val feedback = when (status) {
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                    sourceFingerprint = "5".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CORRECTION,
                    sourceFingerprint = "6".repeat(64),
                    replacementInterpretationFingerprint = "7".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_REJECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_REJECTION,
                    sourceFingerprint = "8".repeat(64),
                )
            else -> null
        }
        val action = if (status == LanguageLearningEpisodeStatus.UNVERIFIED) null else "9".repeat(64)
        val outcome = if (
            status == LanguageLearningEpisodeStatus.UNVERIFIED
        ) null else "a".repeat(64)
        return LanguageLearningEpisode.create(
            interpretation = interpretation,
            actionFingerprint = action,
            outcomeFingerprint = outcome,
            ownerFeedback = feedback,
            status = status,
            sourceCycleId = cycle,
        )
    }

    private fun interpretation(seed: String): LanguageLearningInterpretationRef {
        val utterance = sha("utterance", seed)
        val objective = sha("objective", seed)
        val actionGraph = sha("action", seed)
        val confidence = 0.9
        val fp = sha(
            "language-learning-interpretation-ref/v1",
            utterance,
            "EN",
            "OTHER",
            objective,
            actionGraph,
            java.lang.Double.toHexString(confidence),
        )
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "EN",
            intentName = "OTHER",
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = actionGraph,
            interpretationConfidence = confidence,
            fingerprint = fp,
        )
    }

    private fun sha(domain: String, vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                )
            )
            digest.update(bytes)
        }
        update(domain)
        parts.forEach(::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
