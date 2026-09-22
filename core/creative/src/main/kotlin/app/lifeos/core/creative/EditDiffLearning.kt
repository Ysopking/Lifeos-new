package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds

enum class OwnerEditDiffSignalKind {
    SURFACE_CHANGED,
    TOTAL_TEXT_SHORTER,
    TOTAL_TEXT_LONGER,
    SENTENCES_SHORTER_ON_AVERAGE,
    SENTENCES_LONGER_ON_AVERAGE,
    PARAGRAPHS_SHORTER_ON_AVERAGE,
    PARAGRAPHS_LONGER_ON_AVERAGE,
    FEWER_PARAGRAPHS,
    MORE_PARAGRAPHS,
}

data class OwnerEditDiffSignal(
    val kind: OwnerEditDiffSignalKind,
    val beforeValue: Long,
    val afterValue: Long,
    val fingerprint: String,
) {
    init {
        require(beforeValue >= 0L)
        require(afterValue >= 0L)
        require(
            fingerprint == editSignalFingerprint(
                kind,
                beforeValue,
                afterValue,
            )
        )
    }

    val globalStyleRuleAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
}

data class OwnerEditDiffLearningEvidence(
    val languageTag: String,
    val sourceArtifactFingerprint: String,
    val beforeRevisionFingerprint: String,
    val afterRevisionFingerprint: String,
    val ownerConfirmationFingerprint: String,
    val beforeTextFingerprint: String,
    val afterTextFingerprint: String,
    val signals: List<OwnerEditDiffSignal>,
    val afterStyleSample: OwnerWritingStyleSample,
    val fingerprint: String,
) {
    init {
        require(languageTag.matches(LANGUAGE_TAG_REGEX_B442))
        require(sourceArtifactFingerprint.matches(SHA_256_B442))
        require(beforeRevisionFingerprint.matches(SHA_256_B442))
        require(afterRevisionFingerprint.matches(SHA_256_B442))
        require(beforeRevisionFingerprint != afterRevisionFingerprint)
        require(ownerConfirmationFingerprint.matches(SHA_256_B442))
        require(beforeTextFingerprint.matches(SHA_256_B442))
        require(afterTextFingerprint.matches(SHA_256_B442))
        require(beforeTextFingerprint != afterTextFingerprint)
        require(signals.isNotEmpty())
        require(signals == signals.distinctBy { it.kind }.sortedBy { it.kind.ordinal })
        require(signals.first().kind == OwnerEditDiffSignalKind.SURFACE_CHANGED)
        require(afterStyleSample.languageTag == languageTag)
        require(afterStyleSample.sourceArtifactFingerprint == sourceArtifactFingerprint)
        require(afterStyleSample.sourceRevisionFingerprint == afterRevisionFingerprint)
        require(afterStyleSample.ownerConfirmationFingerprint == ownerConfirmationFingerprint)
        require(afterStyleSample.origin == OwnerWritingSampleOrigin.OWNER_APPROVED)
        require(afterStyleSample.textFingerprint == afterTextFingerprint)
        require(
            fingerprint == editLearningEvidenceFingerprint(
                languageTag,
                sourceArtifactFingerprint,
                beforeRevisionFingerprint,
                afterRevisionFingerprint,
                ownerConfirmationFingerprint,
                beforeTextFingerprint,
                afterTextFingerprint,
                signals,
                afterStyleSample.fingerprint,
            )
        )
    }

    val globalStyleRuleAuthority: Boolean get() = false
    val preferenceAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

sealed interface EditDiffLearningResult {
    data class Learned(
        val evidence: OwnerEditDiffLearningEvidence,
    ) : EditDiffLearningResult

    data class NoChange(
        val sourceArtifactFingerprint: String,
        val beforeRevisionFingerprint: String,
        val afterRevisionFingerprint: String,
        val textFingerprint: String,
        val ownerConfirmationFingerprint: String,
        val fingerprint: String,
    ) : EditDiffLearningResult {
        init {
            require(sourceArtifactFingerprint.matches(SHA_256_B442))
            require(beforeRevisionFingerprint.matches(SHA_256_B442))
            require(afterRevisionFingerprint.matches(SHA_256_B442))
            require(beforeRevisionFingerprint != afterRevisionFingerprint)
            require(textFingerprint.matches(SHA_256_B442))
            require(ownerConfirmationFingerprint.matches(SHA_256_B442))
            require(
                fingerprint == noChangeFingerprint(
                    sourceArtifactFingerprint,
                    beforeRevisionFingerprint,
                    afterRevisionFingerprint,
                    textFingerprint,
                    ownerConfirmationFingerprint,
                )
            )
        }
    }
}

/**
 * B442 turns one explicit owner edit into bounded feedback evidence.
 *
 * The engine retains no raw before/after text. It records exact text fingerprints, deterministic
 * structural deltas and an owner-approved B441 style sample for the edited result. One edit never
 * becomes a global style rule and never authorizes a rewrite or final artifact.
 */
class EditDiffLearningEngine {
    fun analyze(
        languageTag: String,
        sourceArtifactFingerprint: String,
        beforeRevisionFingerprint: String,
        afterRevisionFingerprint: String,
        ownerConfirmationFingerprint: String,
        beforeText: String,
        afterText: String,
    ): EditDiffLearningResult {
        require(beforeRevisionFingerprint != afterRevisionFingerprint) {
            "B442 requires distinct before/after revision fingerprints"
        }
        val language = languageTag.trim().lowercase()
        require(language.matches(LANGUAGE_TAG_REGEX_B442))
        val beforeCanonical = canonicalEditText(beforeText)
        val afterCanonical = canonicalEditText(afterText)
        require(beforeCanonical.isNotBlank())
        require(afterCanonical.isNotBlank())

        val beforeFingerprint = editTextFingerprint(beforeCanonical)
        val afterFingerprint = editTextFingerprint(afterCanonical)
        if (beforeFingerprint == afterFingerprint) {
            return EditDiffLearningResult.NoChange(
                sourceArtifactFingerprint = sourceArtifactFingerprint,
                beforeRevisionFingerprint = beforeRevisionFingerprint,
                afterRevisionFingerprint = afterRevisionFingerprint,
                textFingerprint = beforeFingerprint,
                ownerConfirmationFingerprint = ownerConfirmationFingerprint,
                fingerprint = noChangeFingerprint(
                    sourceArtifactFingerprint,
                    beforeRevisionFingerprint,
                    afterRevisionFingerprint,
                    beforeFingerprint,
                    ownerConfirmationFingerprint,
                ),
            )
        }

        val before = metrics(beforeCanonical)
        val after = metrics(afterCanonical)
        val signals = buildList {
            add(signal(OwnerEditDiffSignalKind.SURFACE_CHANGED, 0L, 1L))
            if (after.characterCount < before.characterCount) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.TOTAL_TEXT_SHORTER,
                        before.characterCount.toLong(),
                        after.characterCount.toLong(),
                    )
                )
            } else if (after.characterCount > before.characterCount) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.TOTAL_TEXT_LONGER,
                        before.characterCount.toLong(),
                        after.characterCount.toLong(),
                    )
                )
            }
            if (after.meanWordsPerSentenceMicros < before.meanWordsPerSentenceMicros) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.SENTENCES_SHORTER_ON_AVERAGE,
                        before.meanWordsPerSentenceMicros,
                        after.meanWordsPerSentenceMicros,
                    )
                )
            } else if (after.meanWordsPerSentenceMicros > before.meanWordsPerSentenceMicros) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.SENTENCES_LONGER_ON_AVERAGE,
                        before.meanWordsPerSentenceMicros,
                        after.meanWordsPerSentenceMicros,
                    )
                )
            }
            if (after.meanCharsPerParagraphMicros < before.meanCharsPerParagraphMicros) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.PARAGRAPHS_SHORTER_ON_AVERAGE,
                        before.meanCharsPerParagraphMicros,
                        after.meanCharsPerParagraphMicros,
                    )
                )
            } else if (after.meanCharsPerParagraphMicros > before.meanCharsPerParagraphMicros) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.PARAGRAPHS_LONGER_ON_AVERAGE,
                        before.meanCharsPerParagraphMicros,
                        after.meanCharsPerParagraphMicros,
                    )
                )
            }
            if (after.paragraphCount < before.paragraphCount) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.FEWER_PARAGRAPHS,
                        before.paragraphCount.toLong(),
                        after.paragraphCount.toLong(),
                    )
                )
            } else if (after.paragraphCount > before.paragraphCount) {
                add(
                    signal(
                        OwnerEditDiffSignalKind.MORE_PARAGRAPHS,
                        before.paragraphCount.toLong(),
                        after.paragraphCount.toLong(),
                    )
                )
            }
        }.distinctBy { it.kind }.sortedBy { it.kind.ordinal }

        val styleSample = OwnerWritingStyleSample.fromText(
            languageTag = language,
            sourceArtifactFingerprint = sourceArtifactFingerprint,
            sourceRevisionFingerprint = afterRevisionFingerprint,
            ownerConfirmationFingerprint = ownerConfirmationFingerprint,
            origin = OwnerWritingSampleOrigin.OWNER_APPROVED,
            text = afterCanonical,
        )
        require(styleSample.textFingerprint == afterFingerprint) {
            "B442/B441 canonical text fingerprint mismatch"
        }

        return EditDiffLearningResult.Learned(
            OwnerEditDiffLearningEvidence(
                languageTag = language,
                sourceArtifactFingerprint = sourceArtifactFingerprint,
                beforeRevisionFingerprint = beforeRevisionFingerprint,
                afterRevisionFingerprint = afterRevisionFingerprint,
                ownerConfirmationFingerprint = ownerConfirmationFingerprint,
                beforeTextFingerprint = beforeFingerprint,
                afterTextFingerprint = afterFingerprint,
                signals = signals,
                afterStyleSample = styleSample,
                fingerprint = editLearningEvidenceFingerprint(
                    language,
                    sourceArtifactFingerprint,
                    beforeRevisionFingerprint,
                    afterRevisionFingerprint,
                    ownerConfirmationFingerprint,
                    beforeFingerprint,
                    afterFingerprint,
                    signals,
                    styleSample.fingerprint,
                ),
            )
        )
    }
}

private data class EditMetrics(
    val sentenceCount: Int,
    val paragraphCount: Int,
    val wordCount: Int,
    val characterCount: Int,
    val meanWordsPerSentenceMicros: Long,
    val meanCharsPerParagraphMicros: Long,
)

private fun metrics(text: String): EditMetrics {
    val paragraphs = text
        .trim()
        .split(Regex("\\n\\s*\\n"))
        .map(String::trim)
        .filter(String::isNotBlank)
    val sentences = text
        .trim()
        .split(Regex("[.!?]+(?:\\s+|$)"))
        .map(String::trim)
        .filter(String::isNotBlank)
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val paragraphCount = paragraphs.size.coerceAtLeast(1)
    val sentenceCount = sentences.size.coerceAtLeast(1)
    val characterCount = text.trim().length
    val wordCount = words.size
    return EditMetrics(
        sentenceCount = sentenceCount,
        paragraphCount = paragraphCount,
        wordCount = wordCount,
        characterCount = characterCount,
        meanWordsPerSentenceMicros =
            wordCount.toLong() * MICROS_B442 / sentenceCount,
        meanCharsPerParagraphMicros =
            characterCount.toLong() * MICROS_B442 / paragraphCount,
    )
}

private fun canonicalEditText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').trim()

private fun editTextFingerprint(value: String): String =
    StableCognitiveIds.fingerprint("owner-edit-text/v1", value)

private fun signal(
    kind: OwnerEditDiffSignalKind,
    beforeValue: Long,
    afterValue: Long,
): OwnerEditDiffSignal =
    OwnerEditDiffSignal(
        kind = kind,
        beforeValue = beforeValue,
        afterValue = afterValue,
        fingerprint = editSignalFingerprint(kind, beforeValue, afterValue),
    )

private fun editSignalFingerprint(
    kind: OwnerEditDiffSignalKind,
    beforeValue: Long,
    afterValue: Long,
): String = StableCognitiveIds.fingerprint(
    "owner-edit-diff-signal/v1",
    kind.name,
    beforeValue.toString(),
    afterValue.toString(),
)

private fun editLearningEvidenceFingerprint(
    languageTag: String,
    sourceArtifactFingerprint: String,
    beforeRevisionFingerprint: String,
    afterRevisionFingerprint: String,
    ownerConfirmationFingerprint: String,
    beforeTextFingerprint: String,
    afterTextFingerprint: String,
    signals: List<OwnerEditDiffSignal>,
    afterStyleSampleFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "owner-edit-diff-learning-evidence/v1",
    languageTag,
    sourceArtifactFingerprint,
    beforeRevisionFingerprint,
    afterRevisionFingerprint,
    ownerConfirmationFingerprint,
    beforeTextFingerprint,
    afterTextFingerprint,
    *signals.map { it.fingerprint }.toTypedArray(),
    afterStyleSampleFingerprint,
)

private fun noChangeFingerprint(
    sourceArtifactFingerprint: String,
    beforeRevisionFingerprint: String,
    afterRevisionFingerprint: String,
    textFingerprint: String,
    ownerConfirmationFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "owner-edit-diff-no-change/v1",
    sourceArtifactFingerprint,
    beforeRevisionFingerprint,
    afterRevisionFingerprint,
    textFingerprint,
    ownerConfirmationFingerprint,
)

private val SHA_256_B442 = Regex("[0-9a-f]{64}")
private val LANGUAGE_TAG_REGEX_B442 =
    Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")
private const val MICROS_B442 = 1_000_000L
