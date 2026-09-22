package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds

enum class OwnerWritingSampleOrigin {
    OWNER_AUTHORED,
    OWNER_APPROVED,
}

data class OwnerWritingStyleSample(
    val languageTag: String,
    val sourceArtifactFingerprint: String,
    val sourceRevisionFingerprint: String,
    val ownerConfirmationFingerprint: String,
    val origin: OwnerWritingSampleOrigin,
    val textFingerprint: String,
    val sentenceCount: Int,
    val paragraphCount: Int,
    val wordCount: Int,
    val characterCount: Int,
    val meanWordsPerSentenceMicros: Long,
    val meanCharsPerParagraphMicros: Long,
    val fingerprint: String,
) {
    init {
        require(languageTag.matches(LANGUAGE_TAG_REGEX_B441))
        require(sourceArtifactFingerprint.matches(SHA_256_B441))
        require(sourceRevisionFingerprint.matches(SHA_256_B441))
        require(ownerConfirmationFingerprint.matches(SHA_256_B441))
        require(textFingerprint.matches(SHA_256_B441))
        require(sentenceCount > 0)
        require(paragraphCount > 0)
        require(wordCount > 0)
        require(characterCount > 0)
        require(meanWordsPerSentenceMicros > 0L)
        require(meanCharsPerParagraphMicros > 0L)
        require(
            fingerprint == styleSampleFingerprint(
                languageTag,
                sourceArtifactFingerprint,
                sourceRevisionFingerprint,
                ownerConfirmationFingerprint,
                origin,
                textFingerprint,
                sentenceCount,
                paragraphCount,
                wordCount,
                characterCount,
                meanWordsPerSentenceMicros,
                meanCharsPerParagraphMicros,
            )
        )
    }

    val preferenceAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false

    companion object {
        fun fromText(
            languageTag: String,
            sourceArtifactFingerprint: String,
            sourceRevisionFingerprint: String,
            ownerConfirmationFingerprint: String,
            origin: OwnerWritingSampleOrigin,
            text: String,
        ): OwnerWritingStyleSample {
            val normalized = text.trim().replace(Regex("[ \\t]+"), " ")
            require(normalized.isNotBlank()) {
                "B441 owner writing sample must contain text"
            }
            val paragraphs = normalized
                .split(Regex("\\n\\s*\\n"))
                .map(String::trim)
                .filter(String::isNotBlank)
            val sentenceUnits = normalized
                .split(Regex("[.!?]+(?:\\s+|$)"))
                .map(String::trim)
                .filter(String::isNotBlank)
            val words = normalized
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
            val sentenceCount = sentenceUnits.size.coerceAtLeast(1)
            val paragraphCount = paragraphs.size.coerceAtLeast(1)
            val characterCount = normalized.length
            val wordCount = words.size
            val meanWords = wordCount.toLong() * MICROS_B441 / sentenceCount
            val meanChars = characterCount.toLong() * MICROS_B441 / paragraphCount
            val language = languageTag.trim().lowercase()
            val textFp = StableCognitiveIds.fingerprint(
                "owner-writing-style-text/v1",
                normalized,
            )
            return OwnerWritingStyleSample(
                languageTag = language,
                sourceArtifactFingerprint = sourceArtifactFingerprint,
                sourceRevisionFingerprint = sourceRevisionFingerprint,
                ownerConfirmationFingerprint = ownerConfirmationFingerprint,
                origin = origin,
                textFingerprint = textFp,
                sentenceCount = sentenceCount,
                paragraphCount = paragraphCount,
                wordCount = wordCount,
                characterCount = characterCount,
                meanWordsPerSentenceMicros = meanWords,
                meanCharsPerParagraphMicros = meanChars,
                fingerprint = styleSampleFingerprint(
                    language,
                    sourceArtifactFingerprint,
                    sourceRevisionFingerprint,
                    ownerConfirmationFingerprint,
                    origin,
                    textFp,
                    sentenceCount,
                    paragraphCount,
                    wordCount,
                    characterCount,
                    meanWords,
                    meanChars,
                ),
            )
        }
    }
}

data class OwnerWritingStyleProfile(
    val languageTag: String,
    val sampleFingerprints: List<String>,
    val sourceArtifactFingerprints: List<String>,
    val ownerConfirmationFingerprints: List<String>,
    val medianWordsPerSentenceMicros: Long,
    val medianCharsPerParagraphMicros: Long,
    val fingerprint: String,
) {
    init {
        require(languageTag.matches(LANGUAGE_TAG_REGEX_B441))
        require(sampleFingerprints.size >= MIN_STYLE_SAMPLES_B441)
        require(sampleFingerprints == sampleFingerprints.distinct().sorted())
        require(
            sourceArtifactFingerprints ==
                sourceArtifactFingerprints.distinct().sorted()
        )
        require(sourceArtifactFingerprints.size >= MIN_STYLE_SAMPLES_B441)
        require(
            ownerConfirmationFingerprints ==
                ownerConfirmationFingerprints.distinct().sorted()
        )
        require(ownerConfirmationFingerprints.isNotEmpty())
        require(medianWordsPerSentenceMicros > 0L)
        require(medianCharsPerParagraphMicros > 0L)
        require(
            fingerprint == styleProfileFingerprint(
                languageTag,
                sampleFingerprints,
                sourceArtifactFingerprints,
                ownerConfirmationFingerprints,
                medianWordsPerSentenceMicros,
                medianCharsPerParagraphMicros,
            )
        )
    }

    val preferenceAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

sealed interface OwnerWritingStyleLearningResult {
    data class Learned(
        val profile: OwnerWritingStyleProfile,
    ) : OwnerWritingStyleLearningResult

    data class InsufficientEvidence(
        val languageTag: String,
        val sampleFingerprints: List<String>,
        val distinctSourceArtifactCount: Int,
        val requiredDistinctSourceArtifactCount: Int,
        val fingerprint: String,
    ) : OwnerWritingStyleLearningResult {
        init {
            require(languageTag.matches(LANGUAGE_TAG_REGEX_B441))
            require(sampleFingerprints == sampleFingerprints.distinct().sorted())
            require(distinctSourceArtifactCount >= 0)
            require(requiredDistinctSourceArtifactCount == MIN_STYLE_SAMPLES_B441)
            require(distinctSourceArtifactCount < requiredDistinctSourceArtifactCount)
            require(
                fingerprint == insufficientStyleEvidenceFingerprint(
                    languageTag,
                    sampleFingerprints,
                    distinctSourceArtifactCount,
                    requiredDistinctSourceArtifactCount,
                )
            )
        }
    }
}

/**
 * B441 learns a descriptive writing-style profile only from explicitly owner-authored or
 * owner-approved samples with exact owner-confirmation evidence.
 *
 * Raw sample text is not retained in the profile. The learner stores deterministic aggregate
 * style measurements and provenance fingerprints. The result describes observed style; it does
 * not become an owner preference command or authorize B440 revisions.
 */
class OwnerWritingStyleLearner {
    fun learn(
        languageTag: String,
        samples: Collection<OwnerWritingStyleSample>,
    ): OwnerWritingStyleLearningResult {
        val language = languageTag.trim().lowercase()
        require(language.matches(LANGUAGE_TAG_REGEX_B441))
        val canonical = samples
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B441 style sample identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }
        require(canonical.all { it.languageTag == language }) {
            "B441 does not mix writing-style languages"
        }

        val distinctSources = canonical
            .map { it.sourceArtifactFingerprint }
            .distinct()
            .sorted()
        if (distinctSources.size < MIN_STYLE_SAMPLES_B441) {
            val sampleFps = canonical.map { it.fingerprint }.sorted()
            return OwnerWritingStyleLearningResult.InsufficientEvidence(
                languageTag = language,
                sampleFingerprints = sampleFps,
                distinctSourceArtifactCount = distinctSources.size,
                requiredDistinctSourceArtifactCount = MIN_STYLE_SAMPLES_B441,
                fingerprint = insufficientStyleEvidenceFingerprint(
                    language,
                    sampleFps,
                    distinctSources.size,
                    MIN_STYLE_SAMPLES_B441,
                ),
            )
        }

        val sampleFps = canonical.map { it.fingerprint }.sorted()
        val confirmations = canonical
            .map { it.ownerConfirmationFingerprint }
            .distinct()
            .sorted()
        val medianWords = median(
            canonical.map { it.meanWordsPerSentenceMicros }
        )
        val medianChars = median(
            canonical.map { it.meanCharsPerParagraphMicros }
        )
        val profile = OwnerWritingStyleProfile(
            languageTag = language,
            sampleFingerprints = sampleFps,
            sourceArtifactFingerprints = distinctSources,
            ownerConfirmationFingerprints = confirmations,
            medianWordsPerSentenceMicros = medianWords,
            medianCharsPerParagraphMicros = medianChars,
            fingerprint = styleProfileFingerprint(
                language,
                sampleFps,
                distinctSources,
                confirmations,
                medianWords,
                medianChars,
            ),
        )
        return OwnerWritingStyleLearningResult.Learned(profile)
    }
}

private fun median(values: List<Long>): Long {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        val lower = sorted[middle - 1]
        val upper = sorted[middle]
        lower + (upper - lower) / 2L
    }
}

private fun styleSampleFingerprint(
    languageTag: String,
    sourceArtifactFingerprint: String,
    sourceRevisionFingerprint: String,
    ownerConfirmationFingerprint: String,
    origin: OwnerWritingSampleOrigin,
    textFingerprint: String,
    sentenceCount: Int,
    paragraphCount: Int,
    wordCount: Int,
    characterCount: Int,
    meanWordsPerSentenceMicros: Long,
    meanCharsPerParagraphMicros: Long,
): String = StableCognitiveIds.fingerprint(
    "owner-writing-style-sample/v1",
    languageTag,
    sourceArtifactFingerprint,
    sourceRevisionFingerprint,
    ownerConfirmationFingerprint,
    origin.name,
    textFingerprint,
    sentenceCount.toString(),
    paragraphCount.toString(),
    wordCount.toString(),
    characterCount.toString(),
    meanWordsPerSentenceMicros.toString(),
    meanCharsPerParagraphMicros.toString(),
)

private fun styleProfileFingerprint(
    languageTag: String,
    sampleFingerprints: List<String>,
    sourceArtifactFingerprints: List<String>,
    ownerConfirmationFingerprints: List<String>,
    medianWordsPerSentenceMicros: Long,
    medianCharsPerParagraphMicros: Long,
): String = StableCognitiveIds.fingerprint(
    "owner-writing-style-profile/v1",
    languageTag,
    sampleFingerprints.joinToString("\u001f"),
    sourceArtifactFingerprints.joinToString("\u001f"),
    ownerConfirmationFingerprints.joinToString("\u001f"),
    medianWordsPerSentenceMicros.toString(),
    medianCharsPerParagraphMicros.toString(),
)

private fun insufficientStyleEvidenceFingerprint(
    languageTag: String,
    sampleFingerprints: List<String>,
    distinctSourceArtifactCount: Int,
    requiredDistinctSourceArtifactCount: Int,
): String = StableCognitiveIds.fingerprint(
    "owner-writing-style-insufficient-evidence/v1",
    languageTag,
    sampleFingerprints.joinToString("\u001f"),
    distinctSourceArtifactCount.toString(),
    requiredDistinctSourceArtifactCount.toString(),
)

private val SHA_256_B441 = Regex("[0-9a-f]{64}")
private val LANGUAGE_TAG_REGEX_B441 =
    Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")
private const val MICROS_B441 = 1_000_000L
private const val MIN_STYLE_SAMPLES_B441 = 2
