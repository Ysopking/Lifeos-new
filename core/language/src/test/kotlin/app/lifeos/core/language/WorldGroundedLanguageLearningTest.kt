package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldGroundedLanguageLearningTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun verifiedOutcomeIsPositiveOnlyWhenInterpretationIsResolved() {
        val result = engine.understand("Suche nach dem Dokument.")
        val evidence = WorldGroundedLanguageLearningEvidence.verifiedOutcome(
            result = result,
            outcomeFingerprint = "a".repeat(64),
            sourceCycleId = "cycle-1",
        )

        assertEquals(
            WorldGroundedLanguageLearningDisposition.POSITIVE_VERIFIED_OUTCOME,
            evidence.disposition,
        )
        assertTrue(evidence.positiveLearningEligible)
        assertFalse(evidence.truthAuthority)
        assertFalse(evidence.lexicalPromotionAuthority)
        assertFalse(evidence.executionAuthority)
    }

    @Test
    fun ambiguousWorldReferenceBlocksPositiveOutcomeLearning() {
        val base = engine.understand("Suche nach dem Dokument.")
        val grounding = LanguageReferenceGrounding(
            expression = ReferenceExpression(
                kind = ReferenceKind.THAT,
                rawText = "das",
                confidence = 0.9,
            ),
            selectedPhotonId = PhotonId("candidate"),
            selectedRevisionRef = PhotonRevisionRef(PhotonId("candidate"), 1L),
            status = LanguageReferenceGroundingStatus.AMBIGUOUS,
            score = 0.70,
            runnerUpScore = 0.67,
            matchedContextFingerprint = "b".repeat(64),
        )
        val result = base.copy(
            goal = base.goal.copy(
                referenceGrounding =
                    LanguageReferenceGroundingState(listOf(grounding)),
            )
        )

        val evidence = WorldGroundedLanguageLearningEvidence.verifiedOutcome(
            result = result,
            outcomeFingerprint = "c".repeat(64),
            sourceCycleId = "cycle-2",
        )

        assertEquals(
            WorldGroundedLanguageLearningDisposition.BLOCKED_WORLD_UNRESOLVED,
            evidence.disposition,
        )
        assertFalse(evidence.positiveLearningEligible)
        assertEquals(null, evidence.episode)
    }

    @Test
    fun explicitOwnerConfirmationIsLanguageEvidenceButNeverTruthAuthority() {
        val result = engine.understand("weiter")
        val feedback = LanguageOwnerFeedback.create(
            kind = LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
            sourceFingerprint = StableCognitiveIds.fingerprint(
                "owner-feedback-source/v1",
                "confirmed",
            ),
        )

        val evidence = WorldGroundedLanguageLearningEvidence.ownerFeedback(
            result = result,
            feedback = feedback,
            sourceCycleId = "cycle-3",
        )

        assertEquals(
            WorldGroundedLanguageLearningDisposition.POSITIVE_OWNER_CONFIRMED,
            evidence.disposition,
        )
        assertTrue(evidence.positiveLearningEligible)
        assertFalse(evidence.truthAuthority)
        assertFalse(evidence.grammarPromotionAuthority)
    }

    @Test
    fun ownerCorrectionAndRejectionAreNegativeEvidence() {
        val result = engine.understand("weiter")
        val correctionReplacement = StableCognitiveIds.fingerprint(
            "replacement-interpretation/v1",
            "other",
        )
        val correction = WorldGroundedLanguageLearningEvidence.ownerFeedback(
            result = result,
            feedback = LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_CORRECTION,
                sourceFingerprint = "d".repeat(64),
                replacementInterpretationFingerprint = correctionReplacement,
            ),
            sourceCycleId = "cycle-4",
        )
        val rejection = WorldGroundedLanguageLearningEvidence.ownerFeedback(
            result = result,
            feedback = LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_REJECTION,
                sourceFingerprint = "e".repeat(64),
            ),
            sourceCycleId = "cycle-5",
        )

        assertTrue(correction.negativeLearningEvidence)
        assertTrue(rejection.negativeLearningEvidence)
        assertFalse(correction.positiveLearningEligible)
        assertFalse(rejection.positiveLearningEligible)
    }
}
