package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseGrammarInductionEngineTest {
    @Test
    fun recurring_owner_phrases_abstract_variable_token_without_promotion_authority() {
        val a = observation(
            episode(LanguageLearningEpisodeStatus.OWNER_CONFIRMED, "cycle-a", "graph-a"),
            listOf("mach", "bitte", "weiter"),
            LexicalLearningScope.OWNER_LANGUAGE,
            'a',
        )
        val b = observation(
            episode(LanguageLearningEpisodeStatus.OWNER_CORRECTED, "cycle-b", "graph-a"),
            listOf("mach", "jetzt", "weiter"),
            LexicalLearningScope.OWNER_LANGUAGE,
            'b',
        )

        val candidate = PhraseGrammarInductionEngine().induce(listOf(a, b)).single()

        assertEquals(
            listOf("mach", "{slot}", "weiter"),
            candidate.patternTokens,
        )
        assertEquals(PhraseGrammarCandidateKind.TOKEN_SLOT_PATTERN, candidate.kind)
        assertEquals(LexicalLearningScope.OWNER_LANGUAGE, candidate.scope)
        assertFalse(candidate.truthAuthority)
        assertFalse(candidate.grammarPromotionAuthority)
        assertFalse(candidate.parserMutationAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun same_phrase_shape_with_different_semantic_graphs_does_not_merge() {
        val a = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-a", "graph-a"),
            listOf("finde", "das"),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            'c',
        )
        val b = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-b", "graph-b"),
            listOf("finde", "das"),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            'd',
        )

        assertTrue(PhraseGrammarInductionEngine().induce(listOf(a, b)).isEmpty())
    }

    @Test
    fun external_language_observation_stays_external() {
        val a = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-a", "graph-x"),
            listOf("look", "this", "up"),
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION,
            'e',
        )
        val b = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-b", "graph-x"),
            listOf("look", "it", "up"),
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION,
            'f',
        )

        val candidate = PhraseGrammarInductionEngine().induce(listOf(a, b)).single()

        assertEquals(LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION, candidate.scope)
        assertFalse(candidate.grammarPromotionAuthority)
    }

    @Test
    fun unverified_episode_cannot_seed_grammar_learning() {
        val episode = episode(LanguageLearningEpisodeStatus.UNVERIFIED, "cycle-u", "graph-u")

        assertFailsWith<IllegalArgumentException> {
            PhraseGrammarObservation.create(
                episode = episode,
                scope = LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
                tokens = listOf("do", "thing"),
                sourceFingerprint = "1".repeat(64),
            )
        }
    }

    @Test
    fun duplicate_replay_and_input_order_are_deterministic() {
        val a = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-a", "graph-d"),
            listOf("mach", "bitte", "weiter"),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            '2',
        )
        val b = observation(
            episode(LanguageLearningEpisodeStatus.VERIFIED_OUTCOME, "cycle-b", "graph-d"),
            listOf("mach", "nun", "weiter"),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            '3',
        )
        val engine = PhraseGrammarInductionEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun observation(
        episode: LanguageLearningEpisode,
        tokens: List<String>,
        scope: LexicalLearningScope,
        seed: Char,
    ): PhraseGrammarObservation =
        PhraseGrammarObservation.create(
            episode = episode,
            scope = scope,
            tokens = tokens,
            sourceFingerprint = seed.toString().repeat(64),
        )

    private fun episode(
        status: LanguageLearningEpisodeStatus,
        cycle: String,
        graphSeed: String,
    ): LanguageLearningEpisode {
        val interpretation = interpretation(graphSeed)
        val feedback = when (status) {
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                    "4".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CORRECTION,
                    "5".repeat(64),
                    replacementInterpretationFingerprint = "6".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_REJECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_REJECTION,
                    "7".repeat(64),
                )
            else -> null
        }
        val action = if (status == LanguageLearningEpisodeStatus.UNVERIFIED) null else "8".repeat(64)
        val outcome = if (status == LanguageLearningEpisodeStatus.UNVERIFIED) null else "9".repeat(64)
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
        val actionGraph = sha("graph", seed)
        val confidence = 0.91
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "DE",
            intentName = "CONTINUE",
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = actionGraph,
            interpretationConfidence = confidence,
            fingerprint = sha(
                "language-learning-interpretation-ref/v1",
                utterance,
                "DE",
                "CONTINUE",
                objective,
                actionGraph,
                java.lang.Double.toHexString(confidence),
            ),
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
