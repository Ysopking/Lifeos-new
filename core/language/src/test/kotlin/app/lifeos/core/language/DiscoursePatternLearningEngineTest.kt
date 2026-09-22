package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoursePatternLearningEngineTest {
    @Test
    fun repeated_follow_up_shape_becomes_non_authoritative_candidate() {
        val shape = DiscourseTransitionShape.create(
            priorIntentName = "QUERY",
            currentIntentName = "CONTINUE",
            activeGoalContinued = true,
            referenceCarriedForward = true,
            clarificationResolved = false,
        )
        val a = observation("cycle-a", shape, 'a')
        val b = observation("cycle-b", shape, 'b')

        val candidate = DiscoursePatternLearningEngine().induce(listOf(a, b)).single()

        assertEquals(shape, candidate.transitionShape)
        assertEquals(listOf("cycle-a", "cycle-b"), candidate.supportingCycleIds)
        assertFalse(candidate.contextMutationAuthority)
        assertFalse(candidate.referenceAuthority)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun different_transition_shapes_are_not_merged() {
        val a = observation(
            "cycle-a",
            DiscourseTransitionShape.create("QUERY", "CONTINUE", true, true, false),
            'c',
        )
        val b = observation(
            "cycle-b",
            DiscourseTransitionShape.create("QUERY", "CONTINUE", false, false, false),
            'd',
        )

        assertTrue(DiscoursePatternLearningEngine().induce(listOf(a, b)).isEmpty())
    }

    @Test
    fun owner_scope_requires_explicit_owner_feedback() {
        val verified = episode(
            LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
            "cycle-owner",
            "CONTINUE",
        )
        val shape = DiscourseTransitionShape.create(
            "QUERY", "CONTINUE", true, true, false
        )

        assertFailsWith<IllegalArgumentException> {
            DiscoursePatternObservation.create(
                episode = verified,
                scope = LexicalLearningScope.OWNER_LANGUAGE,
                beforeDiscourseFingerprint = "1".repeat(64),
                afterDiscourseFingerprint = "2".repeat(64),
                transitionShape = shape,
                sourceFingerprint = "3".repeat(64),
            )
        }
    }

    @Test
    fun external_language_observation_remains_external() {
        val shape = DiscourseTransitionShape.create(
            "QUERY", "CONTINUE", true, false, false
        )
        val a = observation(
            "cycle-a",
            shape,
            'e',
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION,
        )
        val b = observation(
            "cycle-b",
            shape,
            'f',
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION,
        )

        val candidate = DiscoursePatternLearningEngine().induce(listOf(a, b)).single()

        assertEquals(LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION, candidate.scope)
        assertFalse(candidate.promotionAuthority)
    }

    @Test
    fun duplicate_replay_is_idempotent_and_order_independent() {
        val shape = DiscourseTransitionShape.create(
            "QUERY", "CONTINUE", true, true, true
        )
        val a = observation("cycle-a", shape, '4')
        val b = observation("cycle-b", shape, '5')
        val engine = DiscoursePatternLearningEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun observation(
        cycle: String,
        shape: DiscourseTransitionShape,
        seed: Char,
        scope: LexicalLearningScope =
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
    ): DiscoursePatternObservation =
        DiscoursePatternObservation.create(
            episode = episode(
                LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
                cycle,
                shape.currentIntentName,
            ),
            scope = scope,
            beforeDiscourseFingerprint = seed.toString().repeat(64),
            afterDiscourseFingerprint = seed.uppercaseChar().lowercaseChar()
                .toString()
                .repeat(64),
            transitionShape = shape,
            sourceFingerprint = "9".repeat(64),
        )

    private fun episode(
        status: LanguageLearningEpisodeStatus,
        cycle: String,
        intent: String,
    ): LanguageLearningEpisode {
        val interpretation = interpretation(cycle, intent)
        val feedback = when (status) {
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                    "6".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CORRECTION,
                    "7".repeat(64),
                    replacementInterpretationFingerprint = "8".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_REJECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_REJECTION,
                    "a".repeat(64),
                )
            else -> null
        }
        val action = if (status == LanguageLearningEpisodeStatus.UNVERIFIED) null else "b".repeat(64)
        val outcome = if (status == LanguageLearningEpisodeStatus.UNVERIFIED) null else "c".repeat(64)
        return LanguageLearningEpisode.create(
            interpretation = interpretation,
            actionFingerprint = action,
            outcomeFingerprint = outcome,
            ownerFeedback = feedback,
            status = status,
            sourceCycleId = cycle,
        )
    }

    private fun interpretation(
        seed: String,
        intent: String,
    ): LanguageLearningInterpretationRef {
        val utterance = sha("utterance", seed)
        val objective = sha("objective", seed)
        val actionGraph = sha("action", seed)
        val confidence = 0.92
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "DE",
            intentName = intent,
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = actionGraph,
            interpretationConfidence = confidence,
            fingerprint = sha(
                "language-learning-interpretation-ref/v1",
                utterance,
                "DE",
                intent,
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
