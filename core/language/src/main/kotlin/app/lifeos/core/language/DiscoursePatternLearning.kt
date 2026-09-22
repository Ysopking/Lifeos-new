package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class DiscourseTransitionShape(
    val priorIntentName: String,
    val currentIntentName: String,
    val activeGoalContinued: Boolean,
    val referenceCarriedForward: Boolean,
    val clarificationResolved: Boolean,
    val fingerprint: String,
) {
    init {
        require(priorIntentName.isNotBlank())
        require(currentIntentName.isNotBlank())
        require(
            fingerprint == transitionShapeFingerprint(
                priorIntentName,
                currentIntentName,
                activeGoalContinued,
                referenceCarriedForward,
                clarificationResolved,
            )
        )
    }

    companion object {
        fun create(
            priorIntentName: String,
            currentIntentName: String,
            activeGoalContinued: Boolean,
            referenceCarriedForward: Boolean,
            clarificationResolved: Boolean,
        ): DiscourseTransitionShape =
            DiscourseTransitionShape(
                priorIntentName = priorIntentName,
                currentIntentName = currentIntentName,
                activeGoalContinued = activeGoalContinued,
                referenceCarriedForward = referenceCarriedForward,
                clarificationResolved = clarificationResolved,
                fingerprint = transitionShapeFingerprint(
                    priorIntentName,
                    currentIntentName,
                    activeGoalContinued,
                    referenceCarriedForward,
                    clarificationResolved,
                ),
            )
    }
}

data class DiscoursePatternObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val scope: LexicalLearningScope,
    val beforeDiscourseFingerprint: String,
    val afterDiscourseFingerprint: String,
    val transitionShape: DiscourseTransitionShape,
    val cueGrammarCandidateFingerprint: String?,
    val sourceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_REGEX_B424))
        require(episodeStatus != LanguageLearningEpisodeStatus.UNVERIFIED)
        require(sourceCycleId.isNotBlank())
        require(beforeDiscourseFingerprint.matches(SHA_256_REGEX_B424))
        require(afterDiscourseFingerprint.matches(SHA_256_REGEX_B424))
        require(
            cueGrammarCandidateFingerprint == null ||
                cueGrammarCandidateFingerprint.matches(SHA_256_REGEX_B424)
        )
        require(sourceFingerprint.matches(SHA_256_REGEX_B424))
        if (scope == LexicalLearningScope.OWNER_LANGUAGE) {
            require(
                episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                    episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
            ) {
                "Owner-language discourse evidence requires explicit owner confirmation/correction"
            }
        }
        require(
            fingerprint == discourseObservationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                scope,
                beforeDiscourseFingerprint,
                afterDiscourseFingerprint,
                transitionShape,
                cueGrammarCandidateFingerprint,
                sourceFingerprint,
            )
        )
    }

    val contextMutationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            scope: LexicalLearningScope,
            beforeDiscourseFingerprint: String,
            afterDiscourseFingerprint: String,
            transitionShape: DiscourseTransitionShape,
            cueGrammarCandidateFingerprint: String? = null,
            sourceFingerprint: String,
        ): DiscoursePatternObservation {
            require(episode.learningEligible)
            require(transitionShape.currentIntentName == episode.interpretation.intentName) {
                "Discourse transition current intent must bind the B421 interpretation"
            }
            return DiscoursePatternObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                scope = scope,
                beforeDiscourseFingerprint = beforeDiscourseFingerprint,
                afterDiscourseFingerprint = afterDiscourseFingerprint,
                transitionShape = transitionShape,
                cueGrammarCandidateFingerprint = cueGrammarCandidateFingerprint,
                sourceFingerprint = sourceFingerprint,
                fingerprint = discourseObservationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    scope,
                    beforeDiscourseFingerprint,
                    afterDiscourseFingerprint,
                    transitionShape,
                    cueGrammarCandidateFingerprint,
                    sourceFingerprint,
                ),
            )
        }
    }
}

data class DiscoursePatternCandidate(
    val scope: LexicalLearningScope,
    val transitionShape: DiscourseTransitionShape,
    val supportingEpisodeFingerprints: List<String>,
    val supportingCycleIds: List<String>,
    val cueGrammarCandidateFingerprints: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(supportingEpisodeFingerprints.size >= 2)
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(supportingCycleIds.size >= 2)
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(
            cueGrammarCandidateFingerprints ==
                cueGrammarCandidateFingerprints.distinct().sorted()
        )
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == discourseCandidateFingerprint(
                scope,
                transitionShape,
                supportingEpisodeFingerprints,
                supportingCycleIds,
                cueGrammarCandidateFingerprints,
                observationFingerprints,
            )
        )
    }

    val contextMutationAuthority: Boolean get() = false
    val referenceAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B424 learns recurring discourse-transition shapes only.
 *
 * Concrete discourse-state fingerprints remain evidence; the learned unit is the transition shape
 * across independent cycles. Evidence scopes stay isolated and candidates cannot mutate current
 * discourse state, resolve references, promote rules or execute actions.
 */
class DiscoursePatternLearningEngine(
    private val minimumIndependentCycles: Int = 2,
) {
    init {
        require(minimumIndependentCycles in 2..16)
    }

    fun induce(
        observations: Collection<DiscoursePatternObservation>,
    ): List<DiscoursePatternCandidate> {
        if (observations.isEmpty()) return emptyList()

        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting discourse observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy { it.scope to it.transitionShape.fingerprint }
            .mapNotNull { (_, grouped) ->
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
                val cues =
                    independent.mapNotNull { it.cueGrammarCandidateFingerprint }
                        .distinct()
                        .sorted()
                val observationFingerprints =
                    independent.map { it.fingerprint }.distinct().sorted()
                val first = independent.first()

                DiscoursePatternCandidate(
                    scope = first.scope,
                    transitionShape = first.transitionShape,
                    supportingEpisodeFingerprints = episodes,
                    supportingCycleIds = cycles,
                    cueGrammarCandidateFingerprints = cues,
                    observationFingerprints = observationFingerprints,
                    fingerprint = discourseCandidateFingerprint(
                        first.scope,
                        first.transitionShape,
                        episodes,
                        cycles,
                        cues,
                        observationFingerprints,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
    }
}

private fun transitionShapeFingerprint(
    priorIntentName: String,
    currentIntentName: String,
    activeGoalContinued: Boolean,
    referenceCarriedForward: Boolean,
    clarificationResolved: Boolean,
): String = b424Fingerprint(
    "discourse-transition-shape/v1",
    priorIntentName,
    currentIntentName,
    activeGoalContinued.toString(),
    referenceCarriedForward.toString(),
    clarificationResolved.toString(),
)

private fun discourseObservationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    scope: LexicalLearningScope,
    beforeDiscourseFingerprint: String,
    afterDiscourseFingerprint: String,
    transitionShape: DiscourseTransitionShape,
    cueGrammarCandidateFingerprint: String?,
    sourceFingerprint: String,
): String = b424Fingerprint(
    "discourse-pattern-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    scope.name,
    beforeDiscourseFingerprint,
    afterDiscourseFingerprint,
    transitionShape.fingerprint,
    cueGrammarCandidateFingerprint.orEmpty(),
    sourceFingerprint,
)

private fun discourseCandidateFingerprint(
    scope: LexicalLearningScope,
    transitionShape: DiscourseTransitionShape,
    supportingEpisodeFingerprints: List<String>,
    supportingCycleIds: List<String>,
    cueGrammarCandidateFingerprints: List<String>,
    observationFingerprints: List<String>,
): String = b424Fingerprint(
    "discourse-pattern-candidate/v1",
    scope.name,
    transitionShape.fingerprint,
    supportingEpisodeFingerprints.joinToString("\u001f"),
    supportingCycleIds.joinToString("\u001f"),
    cueGrammarCandidateFingerprints.joinToString("\u001f"),
    observationFingerprints.joinToString("\u001f"),
)

private fun b424Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B424 = Regex("[0-9a-f]{64}")
