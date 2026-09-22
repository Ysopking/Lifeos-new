package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import java.util.Locale

enum class PragmaticLearningEvidenceKind {
    OWNER_CONFIRMED,
    OWNER_CORRECTED,
    VERIFIED_USAGE,
    EXTERNAL_OBSERVATION,
}

data class PragmaticLearningObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val scope: LexicalLearningScope,
    val cueTokens: List<String>,
    val pragmaticActType: PragmaticActType,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
    val evidenceKind: PragmaticLearningEvidenceKind,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_B426))
        require(episodeStatus != LanguageLearningEpisodeStatus.UNVERIFIED)
        require(sourceCycleId.isNotBlank())
        require(cueTokens.size in 1..MAX_PRAGMATIC_CUE_TOKENS)
        require(cueTokens == canonicalPragmaticTokens(cueTokens))
        require(pragmaticActType in LEARNABLE_PRAGMATIC_ACTS)
        require(intentName.isNotBlank())
        require(semanticActionGraphFingerprint.matches(SHA_256_B426))
        when (scope) {
            LexicalLearningScope.OWNER_LANGUAGE -> {
                require(
                    episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                        episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
                ) {
                    "Owner pragmatic evidence requires explicit owner feedback"
                }
                require(
                    evidenceKind == PragmaticLearningEvidenceKind.OWNER_CONFIRMED ||
                        evidenceKind == PragmaticLearningEvidenceKind.OWNER_CORRECTED
                )
            }
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE -> {
                require(
                    evidenceKind == PragmaticLearningEvidenceKind.VERIFIED_USAGE
                )
            }
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION -> {
                require(
                    evidenceKind == PragmaticLearningEvidenceKind.EXTERNAL_OBSERVATION
                )
            }
        }
        require(
            fingerprint == pragmaticObservationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                scope,
                cueTokens,
                pragmaticActType,
                intentName,
                semanticActionGraphFingerprint,
                evidenceKind,
            )
        )
    }

    val pragmaticMutationAuthority: Boolean get() = false
    val intentAuthority: Boolean get() = false
    val preferenceAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            scope: LexicalLearningScope,
            cueTokens: List<String>,
            pragmaticAct: PragmaticAct,
        ): PragmaticLearningObservation {
            require(episode.learningEligible)
            require(pragmaticAct.descriptiveOnly) {
                "B426 accepts descriptive pragmatic evidence only"
            }
            val kind = when (scope) {
                LexicalLearningScope.OWNER_LANGUAGE -> when (episode.status) {
                    LanguageLearningEpisodeStatus.OWNER_CONFIRMED ->
                        PragmaticLearningEvidenceKind.OWNER_CONFIRMED
                    LanguageLearningEpisodeStatus.OWNER_CORRECTED ->
                        PragmaticLearningEvidenceKind.OWNER_CORRECTED
                    else -> error("Owner pragmatic evidence requires explicit owner feedback")
                }
                LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE -> {
                    require(
                        episode.status == LanguageLearningEpisodeStatus.VERIFIED_OUTCOME ||
                            episode.status == LanguageLearningEpisodeStatus.OWNER_CONFIRMED
                    )
                    PragmaticLearningEvidenceKind.VERIFIED_USAGE
                }
                LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION ->
                    PragmaticLearningEvidenceKind.EXTERNAL_OBSERVATION
            }

            val canonical = canonicalPragmaticTokens(cueTokens)
            return PragmaticLearningObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                scope = scope,
                cueTokens = canonical,
                pragmaticActType = pragmaticAct.type,
                intentName = episode.interpretation.intentName,
                semanticActionGraphFingerprint =
                    episode.interpretation.semanticActionGraphFingerprint,
                evidenceKind = kind,
                fingerprint = pragmaticObservationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    scope,
                    canonical,
                    pragmaticAct.type,
                    episode.interpretation.intentName,
                    episode.interpretation.semanticActionGraphFingerprint,
                    kind,
                ),
            )
        }
    }
}

data class PragmaticLearningCandidate(
    val scope: LexicalLearningScope,
    val cueTokens: List<String>,
    val pragmaticActType: PragmaticActType,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
    val evidenceKinds: List<PragmaticLearningEvidenceKind>,
    val supportingCycleIds: List<String>,
    val supportingEpisodeFingerprints: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(cueTokens.size in 1..MAX_PRAGMATIC_CUE_TOKENS)
        require(cueTokens == canonicalPragmaticTokens(cueTokens))
        require(pragmaticActType in LEARNABLE_PRAGMATIC_ACTS)
        require(intentName.isNotBlank())
        require(semanticActionGraphFingerprint.matches(SHA_256_B426))
        require(evidenceKinds == evidenceKinds.distinct().sortedBy { it.ordinal })
        require(supportingCycleIds.isNotEmpty())
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == pragmaticCandidateFingerprint(
                scope,
                cueTokens,
                pragmaticActType,
                intentName,
                semanticActionGraphFingerprint,
                evidenceKinds,
                supportingCycleIds,
                supportingEpisodeFingerprints,
                observationFingerprints,
            )
        )
    }

    val pragmaticMutationAuthority: Boolean get() = false
    val intentAuthority: Boolean get() = false
    val preferenceAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B426 learns pragmatic cue candidates from B421 evidence only.
 *
 * Existing PragmaticActResolver remains productive authority. Owner-language candidates require
 * explicit owner confirmation/correction. General-language candidates require repeated independent
 * verified cycles. External observations remain isolated. No candidate can mutate pragmatics,
 * infer a durable preference, promote itself or execute an action.
 */
class PragmaticLearningEngine(
    private val minimumGeneralCycles: Int = 2,
) {
    init {
        require(minimumGeneralCycles in 2..16)
    }

    fun induce(
        observations: Collection<PragmaticLearningObservation>,
    ): List<PragmaticLearningCandidate> {
        if (observations.isEmpty()) return emptyList()

        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B426 pragmatic observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy {
                PragmaticGroupKey(
                    scope = it.scope,
                    cueTokens = it.cueTokens,
                    pragmaticActType = it.pragmaticActType,
                    intentName = it.intentName,
                    semanticActionGraphFingerprint = it.semanticActionGraphFingerprint,
                )
            }
            .mapNotNull { (key, grouped) ->
                val cycles = grouped.map { it.sourceCycleId }.distinct().sorted()
                val ownerExplicit = key.scope == LexicalLearningScope.OWNER_LANGUAGE &&
                    grouped.any {
                        it.evidenceKind == PragmaticLearningEvidenceKind.OWNER_CONFIRMED ||
                            it.evidenceKind == PragmaticLearningEvidenceKind.OWNER_CORRECTED
                    }
                if (!ownerExplicit && cycles.size < minimumGeneralCycles) {
                    return@mapNotNull null
                }

                val kinds = grouped.map { it.evidenceKind }
                    .distinct()
                    .sortedBy { it.ordinal }
                val episodes = grouped.map { it.episodeFingerprint }.distinct().sorted()
                val fingerprints = grouped.map { it.fingerprint }.distinct().sorted()
                PragmaticLearningCandidate(
                    scope = key.scope,
                    cueTokens = key.cueTokens,
                    pragmaticActType = key.pragmaticActType,
                    intentName = key.intentName,
                    semanticActionGraphFingerprint = key.semanticActionGraphFingerprint,
                    evidenceKinds = kinds,
                    supportingCycleIds = cycles,
                    supportingEpisodeFingerprints = episodes,
                    observationFingerprints = fingerprints,
                    fingerprint = pragmaticCandidateFingerprint(
                        key.scope,
                        key.cueTokens,
                        key.pragmaticActType,
                        key.intentName,
                        key.semanticActionGraphFingerprint,
                        kinds,
                        cycles,
                        episodes,
                        fingerprints,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
            .take(MAX_PRAGMATIC_CANDIDATES)
    }
}

private data class PragmaticGroupKey(
    val scope: LexicalLearningScope,
    val cueTokens: List<String>,
    val pragmaticActType: PragmaticActType,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
)

private fun canonicalPragmaticTokens(tokens: List<String>): List<String> =
    tokens.map {
        it.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }.filter(String::isNotBlank)

private fun pragmaticObservationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    scope: LexicalLearningScope,
    cueTokens: List<String>,
    pragmaticActType: PragmaticActType,
    intentName: String,
    semanticActionGraphFingerprint: String,
    evidenceKind: PragmaticLearningEvidenceKind,
): String = StableCognitiveIds.fingerprint(
    "pragmatic-learning-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    scope.name,
    pragmaticActType.name,
    intentName,
    semanticActionGraphFingerprint,
    evidenceKind.name,
    *cueTokens.toTypedArray(),
)

private fun pragmaticCandidateFingerprint(
    scope: LexicalLearningScope,
    cueTokens: List<String>,
    pragmaticActType: PragmaticActType,
    intentName: String,
    semanticActionGraphFingerprint: String,
    evidenceKinds: List<PragmaticLearningEvidenceKind>,
    supportingCycleIds: List<String>,
    supportingEpisodeFingerprints: List<String>,
    observationFingerprints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "pragmatic-learning-candidate/v1",
    scope.name,
    pragmaticActType.name,
    intentName,
    semanticActionGraphFingerprint,
    cueTokens.joinToString("\u001f"),
    evidenceKinds.joinToString("\u001f") { it.name },
    supportingCycleIds.joinToString("\u001f"),
    supportingEpisodeFingerprints.joinToString("\u001f"),
    observationFingerprints.joinToString("\u001f"),
)

private val LEARNABLE_PRAGMATIC_ACTS = setOf(
    PragmaticActType.INDIRECT_REQUEST,
    PragmaticActType.DESIRE,
    PragmaticActType.PREFERENCE,
    PragmaticActType.SUGGESTION,
    PragmaticActType.CONFIRMATION,
    PragmaticActType.CORRECTION,
)
private val SHA_256_B426 = Regex("[0-9a-f]{64}")
private const val MAX_PRAGMATIC_CUE_TOKENS = 8
private const val MAX_PRAGMATIC_CANDIDATES = 64
