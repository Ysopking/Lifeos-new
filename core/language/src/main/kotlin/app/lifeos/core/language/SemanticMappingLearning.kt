package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class SemanticMappingSourceKind {
    LEXICAL_CANDIDATE,
    PHRASE_GRAMMAR_CANDIDATE,
    PRAGMATIC_CANDIDATE,
    DISCOURSE_CANDIDATE,
    REFERENCE_CANDIDATE,
}

data class SemanticMappingObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val scope: LexicalLearningScope,
    val sourceKind: SemanticMappingSourceKind,
    val sourceCandidateFingerprint: String,
    val intentName: String,
    val objectiveFingerprint: String,
    val semanticActionGraphFingerprint: String,
    val evidenceSourceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_B427))
        require(episodeStatus != LanguageLearningEpisodeStatus.UNVERIFIED)
        require(sourceCycleId.isNotBlank())
        require(sourceCandidateFingerprint.matches(SHA_256_B427))
        require(intentName.isNotBlank())
        require(objectiveFingerprint.matches(SHA_256_B427))
        require(semanticActionGraphFingerprint.matches(SHA_256_B427))
        require(evidenceSourceFingerprint.matches(SHA_256_B427))
        if (scope == LexicalLearningScope.OWNER_LANGUAGE) {
            require(
                episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                    episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
            ) {
                "Owner semantic mapping requires explicit owner confirmation/correction"
            }
        }
        require(
            fingerprint == mappingObservationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                scope,
                sourceKind,
                sourceCandidateFingerprint,
                intentName,
                objectiveFingerprint,
                semanticActionGraphFingerprint,
                evidenceSourceFingerprint,
            )
        )
    }

    val mappingAuthority: Boolean get() = false
    val goalMutationAuthority: Boolean get() = false
    val actionGraphMutationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            scope: LexicalLearningScope,
            sourceKind: SemanticMappingSourceKind,
            sourceCandidateFingerprint: String,
            evidenceSourceFingerprint: String,
        ): SemanticMappingObservation {
            require(episode.learningEligible)
            return SemanticMappingObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                scope = scope,
                sourceKind = sourceKind,
                sourceCandidateFingerprint = sourceCandidateFingerprint,
                intentName = episode.interpretation.intentName,
                objectiveFingerprint = episode.interpretation.objectiveFingerprint,
                semanticActionGraphFingerprint =
                    episode.interpretation.semanticActionGraphFingerprint,
                evidenceSourceFingerprint = evidenceSourceFingerprint,
                fingerprint = mappingObservationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    scope,
                    sourceKind,
                    sourceCandidateFingerprint,
                    episode.interpretation.intentName,
                    episode.interpretation.objectiveFingerprint,
                    episode.interpretation.semanticActionGraphFingerprint,
                    evidenceSourceFingerprint,
                ),
            )
        }
    }
}

data class SemanticMappingCandidate(
    val scope: LexicalLearningScope,
    val sourceKind: SemanticMappingSourceKind,
    val sourceCandidateFingerprint: String,
    val intentName: String,
    val objectiveFingerprint: String,
    val semanticActionGraphFingerprint: String,
    val supportingEpisodeFingerprints: List<String>,
    val supportingCycleIds: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(sourceCandidateFingerprint.matches(SHA_256_B427))
        require(intentName.isNotBlank())
        require(objectiveFingerprint.matches(SHA_256_B427))
        require(semanticActionGraphFingerprint.matches(SHA_256_B427))
        require(supportingEpisodeFingerprints.size >= 2)
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(supportingCycleIds.size >= 2)
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == mappingCandidateFingerprint(
                scope,
                sourceKind,
                sourceCandidateFingerprint,
                intentName,
                objectiveFingerprint,
                semanticActionGraphFingerprint,
                supportingEpisodeFingerprints,
                supportingCycleIds,
                observationFingerprints,
            )
        )
    }

    val directMappingAllowed: Boolean get() = false
    val goalAuthority: Boolean get() = false
    val actionGraphAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B427 learns candidate mappings from previously learned language evidence to exact B421 goal and
 * semantic-action-graph identities.
 *
 * Competing mappings remain explicit. No candidate changes productive language understanding,
 * rewrites a goal/action graph, promotes itself, or gains execution authority.
 */
class SemanticMappingLearningEngine(
    private val minimumIndependentCycles: Int = 2,
) {
    init {
        require(minimumIndependentCycles in 2..16)
    }

    fun induce(
        observations: Collection<SemanticMappingObservation>,
    ): List<SemanticMappingCandidate> {
        if (observations.isEmpty()) return emptyList()

        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B427 semantic-mapping observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy {
                MappingKey(
                    scope = it.scope,
                    sourceKind = it.sourceKind,
                    sourceCandidateFingerprint = it.sourceCandidateFingerprint,
                    intentName = it.intentName,
                    objectiveFingerprint = it.objectiveFingerprint,
                    semanticActionGraphFingerprint = it.semanticActionGraphFingerprint,
                )
            }
            .mapNotNull { (key, grouped) ->
                val independent = grouped
                    .groupBy { it.sourceCycleId }
                    .map { (_, sameCycle) -> sameCycle.minBy { it.fingerprint } }
                    .sortedBy { it.fingerprint }
                if (independent.size < minimumIndependentCycles) {
                    return@mapNotNull null
                }

                val episodes =
                    independent.map { it.episodeFingerprint }.distinct().sorted()
                val cycles =
                    independent.map { it.sourceCycleId }.distinct().sorted()
                val evidence =
                    independent.map { it.fingerprint }.distinct().sorted()

                SemanticMappingCandidate(
                    scope = key.scope,
                    sourceKind = key.sourceKind,
                    sourceCandidateFingerprint = key.sourceCandidateFingerprint,
                    intentName = key.intentName,
                    objectiveFingerprint = key.objectiveFingerprint,
                    semanticActionGraphFingerprint = key.semanticActionGraphFingerprint,
                    supportingEpisodeFingerprints = episodes,
                    supportingCycleIds = cycles,
                    observationFingerprints = evidence,
                    fingerprint = mappingCandidateFingerprint(
                        key.scope,
                        key.sourceKind,
                        key.sourceCandidateFingerprint,
                        key.intentName,
                        key.objectiveFingerprint,
                        key.semanticActionGraphFingerprint,
                        episodes,
                        cycles,
                        evidence,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
    }
}

private data class MappingKey(
    val scope: LexicalLearningScope,
    val sourceKind: SemanticMappingSourceKind,
    val sourceCandidateFingerprint: String,
    val intentName: String,
    val objectiveFingerprint: String,
    val semanticActionGraphFingerprint: String,
)

private fun mappingObservationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    scope: LexicalLearningScope,
    sourceKind: SemanticMappingSourceKind,
    sourceCandidateFingerprint: String,
    intentName: String,
    objectiveFingerprint: String,
    semanticActionGraphFingerprint: String,
    evidenceSourceFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "semantic-mapping-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    scope.name,
    sourceKind.name,
    sourceCandidateFingerprint,
    intentName,
    objectiveFingerprint,
    semanticActionGraphFingerprint,
    evidenceSourceFingerprint,
)

private fun mappingCandidateFingerprint(
    scope: LexicalLearningScope,
    sourceKind: SemanticMappingSourceKind,
    sourceCandidateFingerprint: String,
    intentName: String,
    objectiveFingerprint: String,
    semanticActionGraphFingerprint: String,
    supportingEpisodeFingerprints: List<String>,
    supportingCycleIds: List<String>,
    observationFingerprints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "semantic-mapping-candidate/v1",
    scope.name,
    sourceKind.name,
    sourceCandidateFingerprint,
    intentName,
    objectiveFingerprint,
    semanticActionGraphFingerprint,
    supportingEpisodeFingerprints.joinToString("\u001f"),
    supportingCycleIds.joinToString("\u001f"),
    observationFingerprints.joinToString("\u001f"),
)

private val SHA_256_B427 = Regex("[0-9a-f]{64}")
