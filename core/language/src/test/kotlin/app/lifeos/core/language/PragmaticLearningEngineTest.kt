package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PragmaticLearningEngineTest {
    @Test
    fun owner_confirmed_indirect_request_can_form_candidate_without_authority() {
        val episode = ownerEpisode("owner-cycle")
        val observation = PragmaticLearningObservation.create(
            episode = episode,
            scope = LexicalLearningScope.OWNER_LANGUAGE,
            cueTokens = listOf("könntest", "du"),
            pragmaticAct = PragmaticAct(
                PragmaticActType.INDIRECT_REQUEST,
                confidence = 0.94,
                descriptiveOnly = true,
            ),
        )

        val candidate = PragmaticLearningEngine().induce(listOf(observation)).single()

        assertEquals(PragmaticActType.INDIRECT_REQUEST, candidate.pragmaticActType)
        assertFalse(candidate.pragmaticMutationAuthority)
        assertFalse(candidate.preferenceAuthority)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun one_verified_general_usage_is_not_enough_but_two_cycles_are() {
        val a = verifiedObservation("cycle-a")
        val b = verifiedObservation("cycle-b")
        val engine = PragmaticLearningEngine()

        assertTrue(engine.induce(listOf(a)).isEmpty())
        assertEquals(1, engine.induce(listOf(a, b)).size)
    }

    @Test
    fun non_descriptive_pragmatic_evidence_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            PragmaticLearningObservation.create(
                episode = ownerEpisode("cycle-x"),
                scope = LexicalLearningScope.OWNER_LANGUAGE,
                cueTokens = listOf("lieber"),
                pragmaticAct = PragmaticAct(
                    PragmaticActType.PREFERENCE,
                    confidence = 0.9,
                    descriptiveOnly = false,
                ),
            )
        }
    }

    @Test
    fun exact_duplicate_replay_and_input_order_are_deterministic() {
        val a = verifiedObservation("cycle-a")
        val b = verifiedObservation("cycle-b")
        val engine = PragmaticLearningEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun verifiedObservation(cycle: String): PragmaticLearningObservation =
        PragmaticLearningObservation.create(
            episode = verifiedEpisode(cycle),
            scope = LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            cueTokens = listOf("es", "wäre", "gut", "wenn"),
            pragmaticAct = PragmaticAct(
                PragmaticActType.INDIRECT_REQUEST,
                confidence = 0.88,
                descriptiveOnly = true,
            ),
        )

    private fun ownerEpisode(cycle: String): LanguageLearningEpisode {
        val interpretation = interpretation(cycle)
        val feedback = LanguageOwnerFeedback.create(
            kind = LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
            sourceFingerprint = "1".repeat(64),
        )
        return LanguageLearningEpisode.create(
            interpretation = interpretation,
            ownerFeedback = feedback,
            status = LanguageLearningEpisodeStatus.OWNER_CONFIRMED,
            sourceCycleId = cycle,
        )
    }

    private fun verifiedEpisode(cycle: String): LanguageLearningEpisode =
        LanguageLearningEpisode.create(
            interpretation = interpretation(cycle),
            actionFingerprint = "2".repeat(64),
            outcomeFingerprint = "3".repeat(64),
            status = LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
            sourceCycleId = cycle,
        )

    private fun interpretation(seed: String): LanguageLearningInterpretationRef {
        val utterance = sha("utterance", seed)
        val objective = sha("objective", seed)
        val action = sha("action", "shared")
        val confidence = 0.9
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "DE",
            intentName = "SEARCH",
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = action,
            interpretationConfidence = confidence,
            fingerprint = sha(
                "language-learning-interpretation-ref/v1",
                utterance,
                "DE",
                "SEARCH",
                objective,
                action,
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
