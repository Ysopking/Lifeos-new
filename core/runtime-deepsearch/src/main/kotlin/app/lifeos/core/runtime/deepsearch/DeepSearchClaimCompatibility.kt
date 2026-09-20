package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.language.SemanticSearchTerms
import java.util.Locale

data class DeepSearchClaimCompatibilityResult(
    val comparable: Boolean,
    val contradiction: Boolean,
    val reason: String?,
) {
    init {
        require(!contradiction || comparable)
        require(reason == null || reason.isNotBlank())
    }
}

/**
 * Conservative model-free contradiction detector. It only flags obvious conflicts when the two
 * statements have substantial semantic overlap and a single incompatible value/date/polarity.
 */
class DeepSearchClaimCompatibility {
    fun evaluate(
        reference: String,
        candidate: String,
    ): DeepSearchClaimCompatibilityResult {
        val referenceTerms = SemanticSearchTerms.expandedTokens(reference)
        val candidateTerms = SemanticSearchTerms.expandedTokens(candidate)
        if (referenceTerms.isEmpty() || candidateTerms.isEmpty()) {
            return DeepSearchClaimCompatibilityResult(false, false, null)
        }
        val shared = referenceTerms.intersect(candidateTerms)
        val overlap = shared.size.toDouble() /
            minOf(referenceTerms.size, candidateTerms.size).coerceAtLeast(1).toDouble()
        if (shared.size < MIN_SHARED_TERMS || overlap < MIN_OVERLAP) {
            return DeepSearchClaimCompatibilityResult(false, false, null)
        }

        val referenceDates = DATE.findAll(reference).map { normalizeValue(it.value) }.toSet()
        val candidateDates = DATE.findAll(candidate).map { normalizeValue(it.value) }.toSet()
        if (
            referenceDates.size == 1 &&
            candidateDates.size == 1 &&
            referenceDates != candidateDates
        ) {
            return DeepSearchClaimCompatibilityResult(true, true, "date-mismatch")
        }

        val referenceNumbers = NUMBER.findAll(reference).map { normalizeValue(it.value) }.toSet()
        val candidateNumbers = NUMBER.findAll(candidate).map { normalizeValue(it.value) }.toSet()
        if (
            referenceNumbers.size == 1 &&
            candidateNumbers.size == 1 &&
            referenceNumbers != candidateNumbers
        ) {
            return DeepSearchClaimCompatibilityResult(true, true, "numeric-mismatch")
        }

        val referenceNegated = containsNegation(reference)
        val candidateNegated = containsNegation(candidate)
        if (referenceNegated != candidateNegated && overlap >= NEGATION_OVERLAP) {
            return DeepSearchClaimCompatibilityResult(true, true, "polarity-mismatch")
        }

        return DeepSearchClaimCompatibilityResult(true, false, null)
    }

    private fun containsNegation(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return NEGATION.any { cue ->
            Regex("""\b""" + Regex.escape(cue) + """\b""").containsMatchIn(normalized)
        }
    }

    private fun normalizeValue(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace(',', '.')

    private companion object {
        const val MIN_SHARED_TERMS = 2
        const val MIN_OVERLAP = 0.45
        const val NEGATION_OVERLAP = 0.65
        val NUMBER = Regex("""(?<![\p{L}\p{N}])\d+(?:[.,]\d+)?(?![\p{L}\p{N}])""")
        val DATE = Regex("""\b(?:0?[1-9]|[12]\d|3[01])[./-](?:0?[1-9]|1[0-2])(?:[./-](?:19|20)\d{2})?\b""")
        val NEGATION = setOf(
            "nicht", "kein", "keine", "keinen", "niemals", "nie",
            "not", "no", "never", "without",
        )
    }
}
