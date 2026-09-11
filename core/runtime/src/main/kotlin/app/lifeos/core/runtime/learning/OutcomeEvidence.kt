package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class OutcomeEvidenceId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid outcome evidence id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid outcome evidence digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "outcome-evidence:"
    }
}

enum class OutcomeEvidenceSourceClass {
    ACTION_SELF_REPORT,
    SYSTEM_OBSERVATION,
    USER_CORRECTION,
    EXTERNAL_VERIFICATION,
}

data class OutcomeSignal(
    val completion: Double? = null,
    val correctness: Double? = null,
    val usefulness: Double? = null,
    val policyCompliance: Double? = null,
) {
    init {
        val values = listOfNotNull(completion, correctness, usefulness, policyCompliance)
        require(values.isNotEmpty()) { "Outcome evidence requires at least one signal" }
        require(values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Outcome signals must be finite and normalized"
        }
    }

    fun canonicalParts(): List<String> = listOf(
        "completion:${component(completion)}",
        "correctness:${component(correctness)}",
        "usefulness:${component(usefulness)}",
        "policy:${component(policyCompliance)}",
    )

    private fun component(value: Double?): String =
        value?.let(java.lang.Double::toHexString) ?: "-"
}

data class OutcomeEvidence(
    val id: OutcomeEvidenceId,
    val predictionId: OutcomePredictionId,
    val sourceClass: OutcomeEvidenceSourceClass,
    val sourceId: String,
    val sourceFingerprint: String,
    val signal: OutcomeSignal,
    val confidence: Double,
    val independentOfProviderIds: Set<String>,
    val observedAt: Instant,
    val reason: String,
) {
    init {
        require(sourceId.isNotBlank()) { "Outcome evidence source id must not be blank" }
        require(sourceFingerprint.isNotBlank()) { "Outcome evidence source fingerprint must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Outcome evidence confidence must be normalized"
        }
        require(independentOfProviderIds.none { it.isBlank() }) {
            "Outcome evidence provider identities must not be blank"
        }
        require(reason.isNotBlank()) { "Outcome evidence reason must not be blank" }
        if (sourceClass == OutcomeEvidenceSourceClass.ACTION_SELF_REPORT) {
            require(independentOfProviderIds.isEmpty()) {
                "Action self-report cannot declare itself independent of providers"
            }
        }
        require(id == expectedId()) { "Outcome evidence id/content mismatch" }
    }

    fun isIndependentFor(prediction: OutcomePrediction): Boolean {
        if (prediction.id != predictionId) return false
        if (sourceClass == OutcomeEvidenceSourceClass.ACTION_SELF_REPORT) return false
        return prediction.providerIds.all(independentOfProviderIds::contains)
    }

    fun contentFingerprint(): String = fingerprint(
        predictionId = predictionId,
        sourceClass = sourceClass,
        sourceId = sourceId,
        sourceFingerprint = sourceFingerprint,
        signal = signal,
        confidence = confidence,
        independentOfProviderIds = independentOfProviderIds,
        observedAt = observedAt,
        reason = reason,
    )

    private fun expectedId(): OutcomeEvidenceId =
        OutcomeEvidenceId("${OutcomeEvidenceId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            predictionId: OutcomePredictionId,
            sourceClass: OutcomeEvidenceSourceClass,
            sourceId: String,
            sourceFingerprint: String,
            signal: OutcomeSignal,
            confidence: Double,
            independentOfProviderIds: Set<String> = emptySet(),
            observedAt: Instant,
            reason: String,
        ): OutcomeEvidence {
            val fingerprint = fingerprint(
                predictionId = predictionId,
                sourceClass = sourceClass,
                sourceId = sourceId,
                sourceFingerprint = sourceFingerprint,
                signal = signal,
                confidence = confidence,
                independentOfProviderIds = independentOfProviderIds,
                observedAt = observedAt,
                reason = reason,
            )
            return OutcomeEvidence(
                id = OutcomeEvidenceId("${OutcomeEvidenceId.PREFIX}$fingerprint"),
                predictionId = predictionId,
                sourceClass = sourceClass,
                sourceId = sourceId,
                sourceFingerprint = sourceFingerprint,
                signal = signal,
                confidence = confidence,
                independentOfProviderIds = independentOfProviderIds.toSortedSet(),
                observedAt = observedAt,
                reason = reason,
            )
        }

        private fun fingerprint(
            predictionId: OutcomePredictionId,
            sourceClass: OutcomeEvidenceSourceClass,
            sourceId: String,
            sourceFingerprint: String,
            signal: OutcomeSignal,
            confidence: Double,
            independentOfProviderIds: Set<String>,
            observedAt: Instant,
            reason: String,
        ): String = StableFieldIds.fingerprint(
            "outcome-evidence/v1",
            predictionId.value,
            sourceClass.name,
            sourceId,
            sourceFingerprint,
            java.lang.Double.toHexString(confidence),
            observedAt.toString(),
            reason,
            *signal.canonicalParts().toTypedArray(),
            *independentOfProviderIds.sorted().map { "independent:$it" }.toTypedArray(),
        )
    }
}
