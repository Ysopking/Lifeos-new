package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class WorldGroundedLanguageLearningDisposition {
    POSITIVE_OWNER_CONFIRMED,
    POSITIVE_VERIFIED_OUTCOME,
    NEGATIVE_OWNER_CORRECTED,
    NEGATIVE_OWNER_REJECTED,
    BLOCKED_WORLD_UNRESOLVED,
}

data class WorldGroundedLanguageLearningEvidence(
    val interpretation: LanguageLearningInterpretationRef,
    val realizationFingerprint: String,
    val propositionGraphFingerprint: String,
    val referenceGroundingFingerprint: String,
    val temporalModalRealityFingerprint: String,
    val worldEvidenceFingerprint: String?,
    val outcomeFingerprint: String?,
    val ownerFeedback: LanguageOwnerFeedback?,
    val disposition: WorldGroundedLanguageLearningDisposition,
    val sourceCycleId: String,
    val episode: LanguageLearningEpisode?,
    val fingerprint: String,
) {
    init {
        require(realizationFingerprint.isNotBlank())
        require(propositionGraphFingerprint.isNotBlank())
        require(referenceGroundingFingerprint.isNotBlank())
        require(temporalModalRealityFingerprint.isNotBlank())
        require(worldEvidenceFingerprint == null || worldEvidenceFingerprint.isNotBlank())
        require(outcomeFingerprint == null || outcomeFingerprint.matches(SHA_256_B476))
        require(sourceCycleId.isNotBlank())
        require(sourceCycleId.length <= 256)

        when (disposition) {
            WorldGroundedLanguageLearningDisposition.POSITIVE_VERIFIED_OUTCOME -> {
                require(outcomeFingerprint != null)
                require(ownerFeedback == null)
                require(episode?.status == LanguageLearningEpisodeStatus.VERIFIED_OUTCOME)
            }
            WorldGroundedLanguageLearningDisposition.POSITIVE_OWNER_CONFIRMED -> {
                require(outcomeFingerprint == null)
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_CONFIRMATION)
                require(episode?.status == LanguageLearningEpisodeStatus.OWNER_CONFIRMED)
            }
            WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_CORRECTED -> {
                require(outcomeFingerprint == null)
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_CORRECTION)
                require(episode?.status == LanguageLearningEpisodeStatus.OWNER_CORRECTED)
            }
            WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_REJECTED -> {
                require(outcomeFingerprint == null)
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_REJECTION)
                require(episode?.status == LanguageLearningEpisodeStatus.OWNER_REJECTED)
            }
            WorldGroundedLanguageLearningDisposition.BLOCKED_WORLD_UNRESOLVED -> {
                require(ownerFeedback == null)
                require(episode == null)
            }
        }

        require(
            fingerprint == expectedFingerprint(
                interpretation = interpretation,
                realizationFingerprint = realizationFingerprint,
                propositionGraphFingerprint = propositionGraphFingerprint,
                referenceGroundingFingerprint = referenceGroundingFingerprint,
                temporalModalRealityFingerprint = temporalModalRealityFingerprint,
                worldEvidenceFingerprint = worldEvidenceFingerprint,
                outcomeFingerprint = outcomeFingerprint,
                ownerFeedback = ownerFeedback,
                disposition = disposition,
                sourceCycleId = sourceCycleId,
                episode = episode,
            )
        )
    }

    val positiveLearningEligible: Boolean
        get() = disposition in setOf(
            WorldGroundedLanguageLearningDisposition.POSITIVE_OWNER_CONFIRMED,
            WorldGroundedLanguageLearningDisposition.POSITIVE_VERIFIED_OUTCOME,
        )

    val negativeLearningEvidence: Boolean
        get() = disposition in setOf(
            WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_CORRECTED,
            WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_REJECTED,
        )

    val truthAuthority: Boolean
        get() = false

    val lexicalPromotionAuthority: Boolean
        get() = false

    val grammarPromotionAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun verifiedOutcome(
            result: LanguageUnderstandingResult,
            outcomeFingerprint: String,
            sourceCycleId: String,
        ): WorldGroundedLanguageLearningEvidence {
            require(outcomeFingerprint.matches(SHA_256_B476))
            val interpretation = LanguageLearningInterpretationRef.from(result)
            val worldResolved =
                result.goal.interpretationLattice.converged &&
                    !result.goal.interpretationLattice.unresolvedDueToWorldState
            val referencesExact =
                result.goal.referenceGrounding.references.all { it.exactWorldReference }
            val structuralGroundingResolved =
                result.goal.languageRealization.propositions.none { proposition ->
                    proposition.unresolvedReasons.any { reason ->
                        reason == "reference" ||
                            reason == "condition" ||
                            reason.startsWith("role:")
                    }
                }

            if (!worldResolved || !referencesExact || !structuralGroundingResolved) {
                return create(
                    result = result,
                    interpretation = interpretation,
                    outcomeFingerprint = outcomeFingerprint,
                    ownerFeedback = null,
                    disposition =
                        WorldGroundedLanguageLearningDisposition.BLOCKED_WORLD_UNRESOLVED,
                    sourceCycleId = sourceCycleId,
                    episode = null,
                )
            }

            val episode = LanguageLearningEpisode.create(
                interpretation = interpretation,
                actionFingerprint = result.goal.semanticActionGraph.fingerprint,
                outcomeFingerprint = outcomeFingerprint,
                ownerFeedback = null,
                status = LanguageLearningEpisodeStatus.VERIFIED_OUTCOME,
                sourceCycleId = sourceCycleId,
            )
            return create(
                result = result,
                interpretation = interpretation,
                outcomeFingerprint = outcomeFingerprint,
                ownerFeedback = null,
                disposition =
                    WorldGroundedLanguageLearningDisposition.POSITIVE_VERIFIED_OUTCOME,
                sourceCycleId = sourceCycleId,
                episode = episode,
            )
        }

        fun ownerFeedback(
            result: LanguageUnderstandingResult,
            feedback: LanguageOwnerFeedback,
            sourceCycleId: String,
        ): WorldGroundedLanguageLearningEvidence {
            val interpretation = LanguageLearningInterpretationRef.from(result)
            val disposition = when (feedback.kind) {
                LanguageLearningFeedbackKind.OWNER_CONFIRMATION ->
                    WorldGroundedLanguageLearningDisposition.POSITIVE_OWNER_CONFIRMED
                LanguageLearningFeedbackKind.OWNER_CORRECTION ->
                    WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_CORRECTED
                LanguageLearningFeedbackKind.OWNER_REJECTION ->
                    WorldGroundedLanguageLearningDisposition.NEGATIVE_OWNER_REJECTED
            }
            val status = when (feedback.kind) {
                LanguageLearningFeedbackKind.OWNER_CONFIRMATION ->
                    LanguageLearningEpisodeStatus.OWNER_CONFIRMED
                LanguageLearningFeedbackKind.OWNER_CORRECTION ->
                    LanguageLearningEpisodeStatus.OWNER_CORRECTED
                LanguageLearningFeedbackKind.OWNER_REJECTION ->
                    LanguageLearningEpisodeStatus.OWNER_REJECTED
            }
            val episode = LanguageLearningEpisode.create(
                interpretation = interpretation,
                ownerFeedback = feedback,
                status = status,
                sourceCycleId = sourceCycleId,
            )
            return create(
                result = result,
                interpretation = interpretation,
                outcomeFingerprint = null,
                ownerFeedback = feedback,
                disposition = disposition,
                sourceCycleId = sourceCycleId,
                episode = episode,
            )
        }

        private fun create(
            result: LanguageUnderstandingResult,
            interpretation: LanguageLearningInterpretationRef,
            outcomeFingerprint: String?,
            ownerFeedback: LanguageOwnerFeedback?,
            disposition: WorldGroundedLanguageLearningDisposition,
            sourceCycleId: String,
            episode: LanguageLearningEpisode?,
        ): WorldGroundedLanguageLearningEvidence {
            val realizationFingerprint = result.goal.languageRealization.fingerprint
            val propositionGraphFingerprint = result.goal.propositionGraph.fingerprint
            val referenceGroundingFingerprint = result.goal.referenceGrounding.fingerprint
            val temporalModalRealityFingerprint =
                result.goal.temporalModalReality.fingerprint
            val worldEvidenceFingerprint =
                result.goal.interpretationLattice.worldEvidenceFingerprint
            return WorldGroundedLanguageLearningEvidence(
                interpretation = interpretation,
                realizationFingerprint = realizationFingerprint,
                propositionGraphFingerprint = propositionGraphFingerprint,
                referenceGroundingFingerprint = referenceGroundingFingerprint,
                temporalModalRealityFingerprint = temporalModalRealityFingerprint,
                worldEvidenceFingerprint = worldEvidenceFingerprint,
                outcomeFingerprint = outcomeFingerprint,
                ownerFeedback = ownerFeedback,
                disposition = disposition,
                sourceCycleId = sourceCycleId,
                episode = episode,
                fingerprint = expectedFingerprint(
                    interpretation = interpretation,
                    realizationFingerprint = realizationFingerprint,
                    propositionGraphFingerprint = propositionGraphFingerprint,
                    referenceGroundingFingerprint = referenceGroundingFingerprint,
                    temporalModalRealityFingerprint = temporalModalRealityFingerprint,
                    worldEvidenceFingerprint = worldEvidenceFingerprint,
                    outcomeFingerprint = outcomeFingerprint,
                    ownerFeedback = ownerFeedback,
                    disposition = disposition,
                    sourceCycleId = sourceCycleId,
                    episode = episode,
                ),
            )
        }

        private fun expectedFingerprint(
            interpretation: LanguageLearningInterpretationRef,
            realizationFingerprint: String,
            propositionGraphFingerprint: String,
            referenceGroundingFingerprint: String,
            temporalModalRealityFingerprint: String,
            worldEvidenceFingerprint: String?,
            outcomeFingerprint: String?,
            ownerFeedback: LanguageOwnerFeedback?,
            disposition: WorldGroundedLanguageLearningDisposition,
            sourceCycleId: String,
            episode: LanguageLearningEpisode?,
        ): String = StableCognitiveIds.fingerprint(
            "world-grounded-language-learning-evidence/v1",
            interpretation.fingerprint,
            realizationFingerprint,
            propositionGraphFingerprint,
            referenceGroundingFingerprint,
            temporalModalRealityFingerprint,
            worldEvidenceFingerprint.orEmpty(),
            outcomeFingerprint.orEmpty(),
            ownerFeedback?.fingerprint.orEmpty(),
            disposition.name,
            sourceCycleId,
            episode?.fingerprint.orEmpty(),
        )
    }
}

private val SHA_256_B476 = Regex("[0-9a-f]{64}")
