package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class PhraseGrammarCandidateKind {
    FIXED_PHRASE,
    TOKEN_SLOT_PATTERN,
}

data class PhraseGrammarObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val scope: LexicalLearningScope,
    val tokens: List<String>,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
    val sourceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_REGEX_B423))
        require(episodeStatus != LanguageLearningEpisodeStatus.UNVERIFIED)
        require(sourceCycleId.isNotBlank())
        require(tokens.size in MIN_PATTERN_TOKENS..MAX_PATTERN_TOKENS)
        require(tokens == canonicalTokens(tokens))
        require(tokens.none(String::isBlank))
        require(intentName.isNotBlank())
        require(semanticActionGraphFingerprint.matches(SHA_256_REGEX_B423))
        require(sourceFingerprint.matches(SHA_256_REGEX_B423))
        if (scope == LexicalLearningScope.OWNER_LANGUAGE) {
            require(
                episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                    episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
            ) {
                "Owner-language grammar evidence requires explicit owner confirmation/correction"
            }
        }
        require(
            fingerprint == observationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                scope,
                tokens,
                intentName,
                semanticActionGraphFingerprint,
                sourceFingerprint,
            )
        )
    }

    val promotionAuthority: Boolean get() = false
    val parserMutationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            scope: LexicalLearningScope,
            tokens: List<String>,
            sourceFingerprint: String,
        ): PhraseGrammarObservation {
            require(episode.learningEligible)
            val canonical = canonicalTokens(tokens)
            return PhraseGrammarObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                scope = scope,
                tokens = canonical,
                intentName = episode.interpretation.intentName,
                semanticActionGraphFingerprint =
                    episode.interpretation.semanticActionGraphFingerprint,
                sourceFingerprint = sourceFingerprint,
                fingerprint = observationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    scope,
                    canonical,
                    episode.interpretation.intentName,
                    episode.interpretation.semanticActionGraphFingerprint,
                    sourceFingerprint,
                ),
            )
        }
    }
}

data class PhraseGrammarCandidate(
    val kind: PhraseGrammarCandidateKind,
    val scope: LexicalLearningScope,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
    val patternTokens: List<String>,
    val supportingEpisodeFingerprints: List<String>,
    val supportingCycleIds: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(intentName.isNotBlank())
        require(semanticActionGraphFingerprint.matches(SHA_256_REGEX_B423))
        require(patternTokens.size in MIN_PATTERN_TOKENS..MAX_PATTERN_TOKENS)
        require(patternTokens.count { it == SLOT_TOKEN } <= MAX_PATTERN_SLOTS)
        require(patternTokens.count { it != SLOT_TOKEN } >= MIN_FIXED_PATTERN_TOKENS)
        require(
            kind == if (SLOT_TOKEN in patternTokens) {
                PhraseGrammarCandidateKind.TOKEN_SLOT_PATTERN
            } else {
                PhraseGrammarCandidateKind.FIXED_PHRASE
            }
        )
        require(supportingEpisodeFingerprints.size >= 2)
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(supportingCycleIds.size >= 2)
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == candidateFingerprint(
                kind,
                scope,
                intentName,
                semanticActionGraphFingerprint,
                patternTokens,
                supportingEpisodeFingerprints,
                supportingCycleIds,
                observationFingerprints,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val grammarPromotionAuthority: Boolean get() = false
    val parserMutationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B423 induces bounded phrase/grammar candidates from recurring B421 learning episodes.
 *
 * The engine abstracts only token positions that vary across independent cycles while preserving
 * the exact intent and semantic-action-graph identity. The three language evidence scopes remain
 * isolated, and no candidate mutates the productive parser or grammar.
 */
class PhraseGrammarInductionEngine(
    private val minimumIndependentCycles: Int = 2,
) {
    init {
        require(minimumIndependentCycles in 2..16)
    }

    fun induce(
        observations: Collection<PhraseGrammarObservation>,
    ): List<PhraseGrammarCandidate> {
        if (observations.isEmpty()) return emptyList()

        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting phrase/grammar observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy {
                GrammarGroupKey(
                    scope = it.scope,
                    intentName = it.intentName,
                    semanticActionGraphFingerprint = it.semanticActionGraphFingerprint,
                    tokenCount = it.tokens.size,
                )
            }
            .mapNotNull { (_, grouped) ->
                val independent = grouped
                    .groupBy { it.sourceCycleId }
                    .map { (_, sameCycle) -> sameCycle.minBy { it.fingerprint } }
                    .sortedBy { it.fingerprint }

                if (independent.size < minimumIndependentCycles) {
                    return@mapNotNull null
                }

                val pattern = buildPattern(independent.map { it.tokens })
                val slotCount = pattern.count { it == SLOT_TOKEN }
                val fixedCount = pattern.size - slotCount
                if (slotCount > MAX_PATTERN_SLOTS || fixedCount < MIN_FIXED_PATTERN_TOKENS) {
                    return@mapNotNull null
                }

                val episodes =
                    independent.map { it.episodeFingerprint }.distinct().sorted()
                val cycles =
                    independent.map { it.sourceCycleId }.distinct().sorted()
                val fingerprints =
                    independent.map { it.fingerprint }.distinct().sorted()
                val first = independent.first()
                val kind = if (slotCount == 0) {
                    PhraseGrammarCandidateKind.FIXED_PHRASE
                } else {
                    PhraseGrammarCandidateKind.TOKEN_SLOT_PATTERN
                }

                PhraseGrammarCandidate(
                    kind = kind,
                    scope = first.scope,
                    intentName = first.intentName,
                    semanticActionGraphFingerprint =
                        first.semanticActionGraphFingerprint,
                    patternTokens = pattern,
                    supportingEpisodeFingerprints = episodes,
                    supportingCycleIds = cycles,
                    observationFingerprints = fingerprints,
                    fingerprint = candidateFingerprint(
                        kind,
                        first.scope,
                        first.intentName,
                        first.semanticActionGraphFingerprint,
                        pattern,
                        episodes,
                        cycles,
                        fingerprints,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
    }

    private fun buildPattern(
        tokenSequences: List<List<String>>,
    ): List<String> {
        require(tokenSequences.isNotEmpty())
        val size = tokenSequences.first().size
        require(tokenSequences.all { it.size == size })
        return (0 until size).map { index ->
            val terms = tokenSequences.map { it[index] }.distinct()
            if (terms.size == 1) terms.single() else SLOT_TOKEN
        }
    }
}

private data class GrammarGroupKey(
    val scope: LexicalLearningScope,
    val intentName: String,
    val semanticActionGraphFingerprint: String,
    val tokenCount: Int,
)

private fun canonicalTokens(tokens: List<String>): List<String> =
    tokens.map(::normalizeGrammarToken)

private fun normalizeGrammarToken(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun observationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    scope: LexicalLearningScope,
    tokens: List<String>,
    intentName: String,
    semanticActionGraphFingerprint: String,
    sourceFingerprint: String,
): String = b423Fingerprint(
    "phrase-grammar-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    scope.name,
    intentName,
    semanticActionGraphFingerprint,
    sourceFingerprint,
    tokens.joinToString("\u001f"),
)

private fun candidateFingerprint(
    kind: PhraseGrammarCandidateKind,
    scope: LexicalLearningScope,
    intentName: String,
    semanticActionGraphFingerprint: String,
    patternTokens: List<String>,
    supportingEpisodeFingerprints: List<String>,
    supportingCycleIds: List<String>,
    observationFingerprints: List<String>,
): String = b423Fingerprint(
    "phrase-grammar-candidate/v1",
    kind.name,
    scope.name,
    intentName,
    semanticActionGraphFingerprint,
    patternTokens.joinToString("\u001f"),
    supportingEpisodeFingerprints.joinToString("\u001f"),
    supportingCycleIds.joinToString("\u001f"),
    observationFingerprints.joinToString("\u001f"),
)

private fun b423Fingerprint(domain: String, vararg parts: String): String {
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

private const val SLOT_TOKEN = "{slot}"
private const val MIN_PATTERN_TOKENS = 2
private const val MAX_PATTERN_TOKENS = 12
private const val MIN_FIXED_PATTERN_TOKENS = 2
private const val MAX_PATTERN_SLOTS = 4
private val SHA_256_REGEX_B423 = Regex("[0-9a-f]{64}")
