package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ReferenceCoreferenceLearningEngineTest {
    @Test
    fun repeated_owner_confirmed_reference_becomes_inactive_habit_candidate() {
        val bundle = ownerPattern()
        val firstEpisode = bundle.first
        val secondEpisode = bundle.second
        val pattern = bundle.third

        val a = ReferenceHabitObservation.create(
            episode = firstEpisode,
            discoursePattern = pattern,
            referenceSurface = "das",
            targetKind = "goal",
            targetEvidenceFingerprint = "a".repeat(64),
            evidenceKind = ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION,
            sourceFingerprint = "b".repeat(64),
        )
        val b = ReferenceHabitObservation.create(
            episode = secondEpisode,
            discoursePattern = pattern,
            referenceSurface = "das",
            targetKind = "goal",
            targetEvidenceFingerprint = "c".repeat(64),
            evidenceKind = ReferenceLearningEvidenceKind.OWNER_CORRECTED_RESOLUTION,
            sourceFingerprint = "d".repeat(64),
        )

        val candidate = ReferenceCoreferenceLearningEngine().induce(listOf(a, b)).single()

        assertEquals("das", candidate.normalizedReference)
        assertEquals("goal", candidate.targetKind)
        assertFalse(candidate.directResolutionAllowed)
        assertFalse(candidate.referenceAuthority)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.executionAuthority)
    }

    @Test
    fun non_owner_discourse_pattern_is_rejected_for_personal_reference_learning() {
        val episode = episode(
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED,
            "cycle-a",
        )
        val shape = DiscourseTransitionShape.create(
            "QUERY", "CONTINUE", true, true, false
        )
        val pattern = DiscoursePatternCandidate(
            scope = LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            transitionShape = shape,
            supportingEpisodeFingerprints = listOf(
                "1".repeat(64),
                "2".repeat(64),
            ),
            supportingCycleIds = listOf("cycle-a", "cycle-b"),
            cueGrammarCandidateFingerprints = emptyList(),
            observationFingerprints = listOf(
                "3".repeat(64),
                "4".repeat(64),
            ),
            fingerprint = discourseCandidateFingerprintForTest(
                LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
                shape,
                listOf("1".repeat(64), "2".repeat(64)),
                listOf("cycle-a", "cycle-b"),
                emptyList(),
                listOf("3".repeat(64), "4".repeat(64)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ReferenceHabitObservation.create(
                episode,
                pattern,
                "das",
                "goal",
                "5".repeat(64),
                ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION,
                "6".repeat(64),
            )
        }
    }

    @Test
    fun conflicting_target_kinds_remain_explicit_competing_candidates() {
        val bundle = ownerPattern()
        val firstEpisode = bundle.first
        val secondEpisode = bundle.second
        val pattern = bundle.third
        val observations = listOf(
            ReferenceHabitObservation.create(
                firstEpisode,
                pattern,
                "das",
                "goal",
                "7".repeat(64),
                ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION,
                "8".repeat(64),
            ),
            ReferenceHabitObservation.create(
                secondEpisode,
                pattern,
                "das",
                "goal",
                "9".repeat(64),
                ReferenceLearningEvidenceKind.OWNER_CORRECTED_RESOLUTION,
                "a".repeat(64),
            ),
            ReferenceHabitObservation.create(
                firstEpisode,
                pattern,
                "das",
                "image",
                "b".repeat(64),
                ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION,
                "c".repeat(64),
            ),
            ReferenceHabitObservation.create(
                secondEpisode,
                pattern,
                "das",
                "image",
                "d".repeat(64),
                ReferenceLearningEvidenceKind.OWNER_CORRECTED_RESOLUTION,
                "e".repeat(64),
            ),
        )

        val candidates = ReferenceCoreferenceLearningEngine().induce(observations)

        assertEquals(listOf("goal", "image"), candidates.map { it.targetKind }.sorted())
        candidates.forEach { assertFalse(it.referenceAuthority) }
    }

    @Test
    fun duplicate_replay_and_order_are_deterministic() {
        val bundle = ownerPattern()
        val a = ReferenceHabitObservation.create(
            bundle.first,
            bundle.third,
            "das",
            "goal",
            "f".repeat(64),
            ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION,
            "1".repeat(64),
        )
        val b = ReferenceHabitObservation.create(
            bundle.second,
            bundle.third,
            "das",
            "goal",
            "2".repeat(64),
            ReferenceLearningEvidenceKind.OWNER_CORRECTED_RESOLUTION,
            "3".repeat(64),
        )
        val engine = ReferenceCoreferenceLearningEngine()

        assertEquals(
            engine.induce(listOf(a, b)),
            engine.induce(listOf(b, a, a)),
        )
    }

    private fun ownerPattern():
        Triple<LanguageLearningEpisode, LanguageLearningEpisode, DiscoursePatternCandidate> {
        val first = episode(
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED,
            "cycle-a",
        )
        val second = episode(
            LanguageLearningEpisodeStatus.OWNER_CORRECTED,
            "cycle-b",
        )
        val shape = DiscourseTransitionShape.create(
            "QUERY", "CONTINUE", true, true, false
        )
        val firstObservation = DiscoursePatternObservation.create(
            first,
            LexicalLearningScope.OWNER_LANGUAGE,
            "4".repeat(64),
            "5".repeat(64),
            shape,
            sourceFingerprint = "6".repeat(64),
        )
        val secondObservation = DiscoursePatternObservation.create(
            second,
            LexicalLearningScope.OWNER_LANGUAGE,
            "7".repeat(64),
            "8".repeat(64),
            shape,
            sourceFingerprint = "9".repeat(64),
        )
        val pattern = DiscoursePatternLearningEngine()
            .induce(listOf(firstObservation, secondObservation))
            .single()
        return Triple(first, second, pattern)
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
                    "a".repeat(64),
                )
            LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                LanguageOwnerFeedback.create(
                    LanguageLearningFeedbackKind.OWNER_CORRECTION,
                    "b".repeat(64),
                    replacementInterpretationFingerprint = "c".repeat(64),
                )
            else -> error("owner feedback required")
        }
        return LanguageLearningEpisode.create(
            interpretation = interpretation,
            actionFingerprint = "d".repeat(64),
            outcomeFingerprint = "e".repeat(64),
            ownerFeedback = feedback,
            status = status,
            sourceCycleId = cycle,
        )
    }

    private fun interpretation(seed: String): LanguageLearningInterpretationRef {
        val utterance = sha("utterance", seed)
        val objective = sha("objective", seed)
        val action = sha("action", seed)
        val confidence = 0.93
        return LanguageLearningInterpretationRef(
            utteranceFingerprint = utterance,
            languageCode = "DE",
            intentName = "CONTINUE",
            objectiveFingerprint = objective,
            semanticActionGraphFingerprint = action,
            interpretationConfidence = confidence,
            fingerprint = sha(
                "language-learning-interpretation-ref/v1",
                utterance,
                "DE",
                "CONTINUE",
                objective,
                action,
                java.lang.Double.toHexString(confidence),
            ),
        )
    }

    private fun discourseCandidateFingerprintForTest(
        scope: LexicalLearningScope,
        shape: DiscourseTransitionShape,
        episodes: List<String>,
        cycles: List<String>,
        cues: List<String>,
        observations: List<String>,
    ): String = sha(
        "discourse-pattern-candidate/v1",
        scope.name,
        shape.fingerprint,
        episodes.joinToString("\u001f"),
        cycles.joinToString("\u001f"),
        cues.joinToString("\u001f"),
        observations.joinToString("\u001f"),
    )

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
