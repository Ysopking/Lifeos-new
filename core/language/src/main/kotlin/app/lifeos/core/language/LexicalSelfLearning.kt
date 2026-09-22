package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class LexicalLearningScope {
    OWNER_LANGUAGE,
    VERIFIED_GENERAL_LANGUAGE,
    EXTERNAL_LANGUAGE_OBSERVATION,
}

enum class LexicalLearningEvidenceKind {
    OWNER_DEFINITION,
    OWNER_CORRECTION,
    VERIFIED_CONTEXTUAL_USAGE,
    EXTERNAL_OBSERVATION,
}

data class LexicalLearningObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val surfaceForm: String,
    val normalizedForm: String,
    val semanticFingerprint: String,
    val scope: LexicalLearningScope,
    val evidenceKind: LexicalLearningEvidenceKind,
    val sourceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_REGEX_B422))
        require(sourceCycleId.isNotBlank())
        require(surfaceForm.isNotBlank() && surfaceForm.length <= MAX_LEXEME_CHARS)
        require(surfaceForm.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(normalizedForm == normalizeLexeme(surfaceForm))
        require(semanticFingerprint.matches(SHA_256_REGEX_B422))
        require(sourceFingerprint.matches(SHA_256_REGEX_B422))
        require(episodeStatus != LanguageLearningEpisodeStatus.UNVERIFIED)

        when (scope) {
            LexicalLearningScope.OWNER_LANGUAGE -> {
                require(
                    episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                        episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
                ) {
                    "Owner-language lexical evidence requires explicit owner confirmation/correction"
                }
                require(
                    evidenceKind == LexicalLearningEvidenceKind.OWNER_DEFINITION ||
                        evidenceKind == LexicalLearningEvidenceKind.OWNER_CORRECTION
                )
            }
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE ->
                require(evidenceKind == LexicalLearningEvidenceKind.VERIFIED_CONTEXTUAL_USAGE)
            LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION ->
                require(evidenceKind == LexicalLearningEvidenceKind.EXTERNAL_OBSERVATION)
        }

        require(
            fingerprint == lexicalObservationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                surfaceForm,
                normalizedForm,
                semanticFingerprint,
                scope,
                evidenceKind,
                sourceFingerprint,
            )
        )
    }

    val promotionAuthority: Boolean get() = false
    val grammarAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            surfaceForm: String,
            semanticFingerprint: String,
            scope: LexicalLearningScope,
            evidenceKind: LexicalLearningEvidenceKind,
            sourceFingerprint: String,
        ): LexicalLearningObservation {
            require(episode.learningEligible)
            val surface = surfaceForm.trim()
            val normalized = normalizeLexeme(surface)
            return LexicalLearningObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                surfaceForm = surface,
                normalizedForm = normalized,
                semanticFingerprint = semanticFingerprint,
                scope = scope,
                evidenceKind = evidenceKind,
                sourceFingerprint = sourceFingerprint,
                fingerprint = lexicalObservationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    surface,
                    normalized,
                    semanticFingerprint,
                    scope,
                    evidenceKind,
                    sourceFingerprint,
                ),
            )
        }
    }
}

data class LexicalLearningCandidate(
    val normalizedForm: String,
    val variants: List<String>,
    val semanticFingerprint: String,
    val scope: LexicalLearningScope,
    val evidenceKinds: List<LexicalLearningEvidenceKind>,
    val supportingEpisodeFingerprints: List<String>,
    val supportingCycleIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(normalizedForm.isNotBlank())
        require(variants == variants.distinct().sorted())
        require(variants.isNotEmpty())
        require(semanticFingerprint.matches(SHA_256_REGEX_B422))
        require(evidenceKinds == evidenceKinds.distinct().sortedBy { it.ordinal })
        require(supportingEpisodeFingerprints == supportingEpisodeFingerprints.distinct().sorted())
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(
            fingerprint == lexicalCandidateFingerprint(
                normalizedForm,
                variants,
                semanticFingerprint,
                scope,
                evidenceKinds,
                supportingEpisodeFingerprints,
                supportingCycleIds,
            )
        )
    }

    val promotionAuthority: Boolean get() = false
    val parserMutationAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B422 produces lexical learning candidates only.
 *
 * OWNER_LANGUAGE requires explicit owner confirmation/correction. VERIFIED_GENERAL_LANGUAGE needs
 * repeated independent verified usage. EXTERNAL_LANGUAGE_OBSERVATION stays isolated and never
 * becomes owner language by observation alone. No candidate mutates the active parser or lexicon.
 */
class LexicalSelfLearningEngine(
    private val minimumGeneralCycles: Int = 2,
) {
    init {
        require(minimumGeneralCycles in 2..16)
    }

    fun induce(
        observations: Collection<LexicalLearningObservation>,
    ): List<LexicalLearningCandidate> {
        if (observations.isEmpty()) return emptyList()
        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting lexical observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy { Triple(it.scope, it.normalizedForm, it.semanticFingerprint) }
            .mapNotNull { (_, evidence) ->
                val cycles = evidence.map { it.sourceCycleId }.distinct().sorted()
                val eligible = when (evidence.first().scope) {
                    LexicalLearningScope.OWNER_LANGUAGE -> true
                    LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE ->
                        cycles.size >= minimumGeneralCycles
                    LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION -> true
                }
                if (!eligible) return@mapNotNull null

                val variants = evidence.map { it.surfaceForm }.distinct().sorted()
                val episodeFingerprints =
                    evidence.map { it.episodeFingerprint }.distinct().sorted()
                val kinds =
                    evidence.map { it.evidenceKind }.distinct().sortedBy { it.ordinal }
                val first = evidence.first()
                LexicalLearningCandidate(
                    normalizedForm = first.normalizedForm,
                    variants = variants,
                    semanticFingerprint = first.semanticFingerprint,
                    scope = first.scope,
                    evidenceKinds = kinds,
                    supportingEpisodeFingerprints = episodeFingerprints,
                    supportingCycleIds = cycles,
                    fingerprint = lexicalCandidateFingerprint(
                        first.normalizedForm,
                        variants,
                        first.semanticFingerprint,
                        first.scope,
                        kinds,
                        episodeFingerprints,
                        cycles,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
    }
}

private fun normalizeLexeme(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun lexicalObservationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    surfaceForm: String,
    normalizedForm: String,
    semanticFingerprint: String,
    scope: LexicalLearningScope,
    evidenceKind: LexicalLearningEvidenceKind,
    sourceFingerprint: String,
): String = b422Fingerprint(
    "lexical-learning-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    surfaceForm,
    normalizedForm,
    semanticFingerprint,
    scope.name,
    evidenceKind.name,
    sourceFingerprint,
)

private fun lexicalCandidateFingerprint(
    normalizedForm: String,
    variants: List<String>,
    semanticFingerprint: String,
    scope: LexicalLearningScope,
    evidenceKinds: List<LexicalLearningEvidenceKind>,
    supportingEpisodeFingerprints: List<String>,
    supportingCycleIds: List<String>,
): String = b422Fingerprint(
    "lexical-learning-candidate/v1",
    normalizedForm,
    semanticFingerprint,
    scope.name,
    variants.joinToString("\u001f"),
    evidenceKinds.joinToString("\u001f") { it.name },
    supportingEpisodeFingerprints.joinToString("\u001f"),
    supportingCycleIds.joinToString("\u001f"),
)

private fun b422Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B422 = Regex("[0-9a-f]{64}")
private const val MAX_LEXEME_CHARS = 160
