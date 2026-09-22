package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class LanguageLearningFeedbackKind {
    OWNER_CONFIRMATION,
    OWNER_CORRECTION,
    OWNER_REJECTION,
}

enum class LanguageLearningEpisodeStatus {
    UNVERIFIED,
    VERIFIED_OUTCOME,
    OWNER_CONFIRMED,
    OWNER_CORRECTED,
    OWNER_REJECTED,
}

data class LanguageLearningInterpretationRef(
    val utteranceFingerprint: String,
    val languageCode: String,
    val intentName: String,
    val objectiveFingerprint: String,
    val semanticActionGraphFingerprint: String,
    val interpretationConfidence: Double,
    val fingerprint: String,
) {
    init {
        require(utteranceFingerprint.matches(SHA_256_REGEX_B421))
        require(languageCode.isNotBlank())
        require(intentName.isNotBlank())
        require(objectiveFingerprint.matches(SHA_256_REGEX_B421))
        require(semanticActionGraphFingerprint.matches(SHA_256_REGEX_B421))
        require(interpretationConfidence.isFinite() && interpretationConfidence in 0.0..1.0)
        require(
            fingerprint == interpretationFingerprint(
                utteranceFingerprint,
                languageCode,
                intentName,
                objectiveFingerprint,
                semanticActionGraphFingerprint,
                interpretationConfidence,
            )
        )
    }

    companion object {
        fun from(result: LanguageUnderstandingResult): LanguageLearningInterpretationRef {
            val utteranceFingerprint = b421Fingerprint(
                "language-learning-utterance/v1",
                result.utterance.normalized,
                result.utterance.language.name,
            )
            val objectiveFingerprint = b421Fingerprint(
                "language-learning-objective/v1",
                result.goal.objective,
            )
            val fingerprint = interpretationFingerprint(
                utteranceFingerprint = utteranceFingerprint,
                languageCode = result.utterance.language.name,
                intentName = result.goal.intent.name,
                objectiveFingerprint = objectiveFingerprint,
                semanticActionGraphFingerprint = result.goal.semanticActionGraph.fingerprint,
                interpretationConfidence = result.goal.confidence,
            )
            return LanguageLearningInterpretationRef(
                utteranceFingerprint = utteranceFingerprint,
                languageCode = result.utterance.language.name,
                intentName = result.goal.intent.name,
                objectiveFingerprint = objectiveFingerprint,
                semanticActionGraphFingerprint = result.goal.semanticActionGraph.fingerprint,
                interpretationConfidence = result.goal.confidence,
                fingerprint = fingerprint,
            )
        }
    }
}

data class LanguageOwnerFeedback(
    val kind: LanguageLearningFeedbackKind,
    val sourceFingerprint: String,
    val replacementInterpretationFingerprint: String? = null,
    val ownerConfirmed: Boolean,
    val fingerprint: String,
) {
    init {
        require(sourceFingerprint.matches(SHA_256_REGEX_B421))
        require(ownerConfirmed) {
            "B421 accepts explicit owner feedback only"
        }
        when (kind) {
            LanguageLearningFeedbackKind.OWNER_CORRECTION ->
                require(replacementInterpretationFingerprint?.matches(SHA_256_REGEX_B421) == true) {
                    "Owner correction requires the exact replacement interpretation fingerprint"
                }
            LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
            LanguageLearningFeedbackKind.OWNER_REJECTION ->
                require(replacementInterpretationFingerprint == null)
        }
        require(
            fingerprint == ownerFeedbackFingerprint(
                kind,
                sourceFingerprint,
                replacementInterpretationFingerprint,
                ownerConfirmed,
            )
        )
    }

    companion object {
        fun create(
            kind: LanguageLearningFeedbackKind,
            sourceFingerprint: String,
            replacementInterpretationFingerprint: String? = null,
            ownerConfirmed: Boolean = true,
        ): LanguageOwnerFeedback {
            require(ownerConfirmed)
            val fingerprint = ownerFeedbackFingerprint(
                kind,
                sourceFingerprint,
                replacementInterpretationFingerprint,
                ownerConfirmed,
            )
            return LanguageOwnerFeedback(
                kind = kind,
                sourceFingerprint = sourceFingerprint,
                replacementInterpretationFingerprint = replacementInterpretationFingerprint,
                ownerConfirmed = ownerConfirmed,
                fingerprint = fingerprint,
            )
        }
    }
}

data class LanguageLearningEpisode(
    val interpretation: LanguageLearningInterpretationRef,
    val actionFingerprint: String?,
    val outcomeFingerprint: String?,
    val ownerFeedback: LanguageOwnerFeedback?,
    val status: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val fingerprint: String,
) {
    init {
        require(actionFingerprint == null || actionFingerprint.matches(SHA_256_REGEX_B421))
        require(outcomeFingerprint == null || outcomeFingerprint.matches(SHA_256_REGEX_B421))
        require(sourceCycleId.isNotBlank())
        require(sourceCycleId.length <= MAX_CYCLE_ID_CHARS)

        when (status) {
            LanguageLearningEpisodeStatus.UNVERIFIED -> {
                require(outcomeFingerprint == null)
                require(ownerFeedback == null)
            }
            LanguageLearningEpisodeStatus.VERIFIED_OUTCOME -> {
                require(actionFingerprint != null)
                require(outcomeFingerprint != null)
                require(ownerFeedback == null)
            }
            LanguageLearningEpisodeStatus.OWNER_CONFIRMED -> {
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_CONFIRMATION)
            }
            LanguageLearningEpisodeStatus.OWNER_CORRECTED -> {
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_CORRECTION)
            }
            LanguageLearningEpisodeStatus.OWNER_REJECTED -> {
                require(ownerFeedback?.kind == LanguageLearningFeedbackKind.OWNER_REJECTION)
            }
        }

        if (actionFingerprint != null) {
            require(
                outcomeFingerprint != null ||
                    status == LanguageLearningEpisodeStatus.UNVERIFIED
            ) {
                "A completed language-learning action requires exact outcome evidence"
            }
        }

        require(
            fingerprint == episodeFingerprint(
                interpretation,
                actionFingerprint,
                outcomeFingerprint,
                ownerFeedback,
                status,
                sourceCycleId,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val grammarPromotionAuthority: Boolean get() = false
    val lexicalPromotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    val learningEligible: Boolean
        get() = status != LanguageLearningEpisodeStatus.UNVERIFIED

    companion object {
        fun create(
            interpretation: LanguageLearningInterpretationRef,
            actionFingerprint: String? = null,
            outcomeFingerprint: String? = null,
            ownerFeedback: LanguageOwnerFeedback? = null,
            status: LanguageLearningEpisodeStatus,
            sourceCycleId: String,
        ): LanguageLearningEpisode =
            LanguageLearningEpisode(
                interpretation = interpretation,
                actionFingerprint = actionFingerprint,
                outcomeFingerprint = outcomeFingerprint,
                ownerFeedback = ownerFeedback,
                status = status,
                sourceCycleId = sourceCycleId,
                fingerprint = episodeFingerprint(
                    interpretation,
                    actionFingerprint,
                    outcomeFingerprint,
                    ownerFeedback,
                    status,
                    sourceCycleId,
                ),
            )
    }
}

private fun interpretationFingerprint(
    utteranceFingerprint: String,
    languageCode: String,
    intentName: String,
    objectiveFingerprint: String,
    semanticActionGraphFingerprint: String,
    interpretationConfidence: Double,
): String = b421Fingerprint(
    "language-learning-interpretation-ref/v1",
    utteranceFingerprint,
    languageCode,
    intentName,
    objectiveFingerprint,
    semanticActionGraphFingerprint,
    java.lang.Double.toHexString(interpretationConfidence),
)

private fun ownerFeedbackFingerprint(
    kind: LanguageLearningFeedbackKind,
    sourceFingerprint: String,
    replacementInterpretationFingerprint: String?,
    ownerConfirmed: Boolean,
): String = b421Fingerprint(
    "language-owner-feedback/v1",
    kind.name,
    sourceFingerprint,
    replacementInterpretationFingerprint.orEmpty(),
    ownerConfirmed.toString(),
)

private fun episodeFingerprint(
    interpretation: LanguageLearningInterpretationRef,
    actionFingerprint: String?,
    outcomeFingerprint: String?,
    ownerFeedback: LanguageOwnerFeedback?,
    status: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
): String = b421Fingerprint(
    "language-learning-episode/v1",
    interpretation.fingerprint,
    actionFingerprint.orEmpty(),
    outcomeFingerprint.orEmpty(),
    ownerFeedback?.fingerprint.orEmpty(),
    status.name,
    sourceCycleId,
)

private fun b421Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B421 = Regex("[0-9a-f]{64}")
private const val MAX_CYCLE_ID_CHARS = 256
