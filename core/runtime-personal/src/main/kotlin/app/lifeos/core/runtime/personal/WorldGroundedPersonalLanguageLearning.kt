package app.lifeos.core.runtime.personal

import app.lifeos.core.language.WorldGroundedLanguageLearningEvidence
import app.lifeos.core.model.StableCognitiveIds

data class PersonalLanguageOutcomeEvidenceBinding(
    val candidateFingerprint: String,
    val learningEvidence: WorldGroundedLanguageLearningEvidence,
) {
    init {
        require(candidateFingerprint.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "personal-language-outcome-evidence-binding/v1",
        candidateFingerprint,
        learningEvidence.fingerprint,
    )

    val promotionAuthority: Boolean
        get() = false

    companion object {
        fun bind(
            candidate: PersonalLanguageCandidate,
            evidence: WorldGroundedLanguageLearningEvidence,
        ): PersonalLanguageOutcomeEvidenceBinding =
            PersonalLanguageOutcomeEvidenceBinding(
                candidateFingerprint = candidate.fingerprint,
                learningEvidence = evidence,
            )
    }
}

sealed interface OutcomeGuardedPersonalLanguagePromotionResult {
    data class Promoted(
        val delegate: DurablePersonalLanguagePromotionResult.Promoted,
        val evidenceFingerprint: String,
    ) : OutcomeGuardedPersonalLanguagePromotionResult

    data class Rejected(
        val reason: String,
        val delegate: DurablePersonalLanguagePromotionResult.Rejected? = null,
    ) : OutcomeGuardedPersonalLanguagePromotionResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

/**
 * B476 evidence gate in front of the existing durable promotion coordinator.
 *
 * It does not replace the existing support policy or shadow regression gate. It only ensures that
 * outcome/owner feedback attached to a candidate is exact, positive and unopposed before the
 * existing promotion authority is invoked.
 */
class OutcomeGuardedPersonalLanguagePromotionCoordinator(
    private val delegate: DurablePersonalLanguagePromotionCoordinator,
    private val minimumPositiveEvidence: Int = 1,
) {
    init {
        require(minimumPositiveEvidence >= 1)
    }

    suspend fun promote(
        candidate: PersonalLanguageCandidate,
        evidence: Collection<PersonalLanguageOutcomeEvidenceBinding>,
    ): OutcomeGuardedPersonalLanguagePromotionResult {
        val bound = evidence
            .filter { it.candidateFingerprint == candidate.fingerprint }
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }

        if (bound.any { it.learningEvidence.negativeLearningEvidence }) {
            return OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                "negative-owner-evidence"
            )
        }

        val positive = bound.filter {
            it.learningEvidence.positiveLearningEligible
        }
        if (positive.size < minimumPositiveEvidence) {
            return OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                "insufficient-world-grounded-learning-evidence"
            )
        }

        return when (val result = delegate.promote(candidate)) {
            is DurablePersonalLanguagePromotionResult.Promoted ->
                OutcomeGuardedPersonalLanguagePromotionResult.Promoted(
                    delegate = result,
                    evidenceFingerprint = StableCognitiveIds.fingerprint(
                        "personal-language-promotion-evidence-set/v1",
                        candidate.fingerprint,
                        *positive.map { it.fingerprint }.toTypedArray(),
                    ),
                )
            is DurablePersonalLanguagePromotionResult.Rejected ->
                OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                    reason = result.reason,
                    delegate = result,
                )
        }
    }
}
