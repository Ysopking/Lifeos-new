package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SemanticMappingLearningEngineTest {
    @Test
    fun repeated_owner_mapping_becomes_inactive_candidate() {
        val first = ownerEpisode(
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED,
            "cycle-a",
            "shared",
        )
        val second = ownerEpisode(
            LanguageLearningEpisodeStatus.OWNER_CORRECTED,
            "cycle-b",
            "shared",
        )
        val source = "1".repeat(64)
        val a = SemanticMappingObservation.create(
            first,
            LexicalLearningScope.OWNER_LANGUAGE,
            SemanticMappingSourceKind.PHRASE_GRAMMAR_CANDIDATE,
            source,
            "2".repeat(64),
        )
        val b = SemanticMappingObservation.create(
            second,
            LexicalLearningScope.OWNER_LANGUAGE,
            SemanticMappingSourceKind.PHRASE_GRAMMAR_CANDIDATE,
            source,
            "3".repeat(64),
        )

        val candidate = SemanticMappingLearningEngine().induce(listOf(a, b)).single()

        assertEquals("CONTINUE", candidate.intentName)
        assertEquals(source, candidate.sourceCandidateFingerprint)
        assertFalse(candidate.directMappingAllowed)
        assertFalse(candidate.goalAuthority)
        assertFalse(candidate.actionGraphAuthority)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun same_source_with_different_action_graphs_remains_competing() {
        val source = "4".repeat(64)
        val observations = listOf(
            generalObservation("a1", "target-a", source, "5"),
            generalObservation("a2", "target-a", source, "6"),
            generalObservation("b1", "target-b", source, "7"),
            generalObservation("b2", "target-b", source, "8"),
        )

        val candidates = SemanticMappingLearningEngine().induce(observations)

        assertEquals(2, candidates.size)
        candidates.forEach {
            assertFalse(it.directMappingAllowed)
            assertFalse(it.actionGraphAuthority)
        }
    }

    @Test
    fun owner_scope_rejects_merely_verified_episode() {
        val episode = verifiedEpisode("cycle-v", "shared")

        assertFailsWith<IllegalArgumentException> {
            SemanticMappingObservation.create(
                episode,
                LexicalLearningScope.OWNER_LANGUAGE,
                SemanticMappingSourceKind.PRAGMATIC_CANDIDATE,
                "9".repeat(64),
                "a".repeat(64),
            )
        }
    }

    @Test
    fun duplicate_replay_and_order_are_deterministic() {
        val source = "b".repeat(64)
        val a = generalObservation("cycle-a", "same", source, "c")
        val b = generalObservation("cycle-b", "same", source, "d")
        val engine = SemanticMappingLearningEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun generalObservation(
        cycle: String,
        targetSeed: String,
        sourceCandidate: String,
        evidenceSeed: String,
    ): SemanticMappingObservation =
        SemanticMappingObservation.create(
            episode = verifiedEpisode(cycle, targetSeed),
            scope = LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            sourceKind = SemanticMappingSourceKind.PRAGMATIC_CANDIDATE,
            sourceCandidateFingerprint = sourceCandidate,
            evidenceSourceFingerprint = sha("evidence", evidenceSeed),
        )

    private fun ownerEpisode(
        status: LanguageLearningEpisodeStatus,
        cycle: String,
        targetSeed: String,
    ): LanguageLearningEpisode {
        val feedback = when (status) {
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                    "e".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CORRECTION,
                    "f".repeat(64),
                    replacementInterpretationFingerprint = "1".repeat(64),
                )
            else -> error("owner status required")
        }
        return LanguageLearningEpisode.create(
            interpretation = interpretation(targetSeed),
            actionFingerprint = "2".repeat(64),
            outcomeFingerprint = "3".repeat(64),
            ownerFeedback = feedback,
            status = status,
            sourceCycleId = cycle,
        )
    }

    private fun verifiedEpisode(
        cycle: String,
        targetSeed: String,
    ): LanguageLearningEpisode =
        LanguageLearningEpisode.create(
            interpretation = interpretation(targetSeed),
            actionFingerprint = "4".repeat(64),
            outcomeFingerprint = "5".repeat(64),
            status = LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
            sourceCycleId = cycle,
        )

    private fun interpretation(seed: String): LanguageLearningInterpretationRef {
        val utterance = sha("utterance", seed)
        val objective = sha("objective", seed)
        val graph = sha("graph", seed)
        val confidence = 0.94
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "DE",
            intentName = "CONTINUE",
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = graph,
            interpretationConfidence = confidence,
            fingerprint = sha(
                "language-learning-interpretation-ref/v1",
                utterance,
                "DE",
                "CONTINUE",
                objective,
                graph,
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
