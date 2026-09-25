package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import java.math.BigInteger

enum class PredictiveEquivalenceDecision {
    MERGE,
    SPLIT,
    UNRESOLVED,
}

data class EmpiricalFutureCounts private constructor(
    val counts: Map<String, Long>,
    val sampleCount: Long,
    val fingerprint: String,
) {
    init {
        require(counts.isNotEmpty()) {
            "Empirical future counts require at least one observed outcome"
        }
        require(counts.keys.none { it.isBlank() })
        require(counts.values.all { it > 0L }) {
            "Empirical future counts store only positive observations"
        }
        require(counts.keys.toList() == counts.keys.sorted()) {
            "Empirical future counts must be canonical"
        }
        require(sampleCount == counts.values.sum()) {
            "Empirical future sample count does not match counts"
        }
        require(sampleCount > 0L)
        require(fingerprint == expectedFingerprint(counts)) {
            "Empirical future-count fingerprint does not match observations"
        }
    }

    companion object {
        fun create(
            counts: Map<String, Long>,
        ): EmpiricalFutureCounts {
            require(counts.isNotEmpty())
            require(counts.keys.none { it.isBlank() })
            require(counts.values.all { it > 0L })
            val canonical = counts.toSortedMap()
            val total = canonical.values.fold(0L) { acc, value ->
                Math.addExact(acc, value)
            }
            return EmpiricalFutureCounts(
                counts = canonical,
                sampleCount = total,
                fingerprint = expectedFingerprint(canonical),
            )
        }

        private fun expectedFingerprint(
            counts: Map<String, Long>,
        ): String = StableFieldIds.fingerprint(
            "empirical-future-counts/v1",
            *counts.map { (outcomeId, count) ->
                "$outcomeId:$count"
            }.toTypedArray(),
        )
    }
}

data class EmpiricalPredictiveHistory(
    val realizationProfileFingerprint: String,
    val historyFingerprint: String,
    val observations: EmpiricalFutureCounts,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(historyFingerprint.isNotBlank())
    }
}

data class PredictiveEquivalencePolicy(
    val minimumSamplesPerHistory: Long = 20L,
    val mergeMaximumDistanceMicros: Long = 50_000L,
    val splitMinimumDistanceMicros: Long = 150_000L,
) {
    init {
        require(minimumSamplesPerHistory > 0L)
        require(mergeMaximumDistanceMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
        require(splitMinimumDistanceMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
        require(mergeMaximumDistanceMicros < splitMinimumDistanceMicros) {
            "Predictive MERGE and SPLIT thresholds require an unresolved interval"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "predictive-equivalence-policy/v1",
        minimumSamplesPerHistory.toString(),
        mergeMaximumDistanceMicros.toString(),
        splitMinimumDistanceMicros.toString(),
    )
}

data class PredictiveEquivalenceAssessment(
    val realizationProfileFingerprint: String,
    val firstHistoryFingerprint: String,
    val secondHistoryFingerprint: String,
    val decision: PredictiveEquivalenceDecision,
    val totalVariationMicros: Long?,
    val firstSampleCount: Long,
    val secondSampleCount: Long,
    val policyFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(firstHistoryFingerprint.isNotBlank())
        require(secondHistoryFingerprint.isNotBlank())
        require(firstHistoryFingerprint < secondHistoryFingerprint) {
            "Predictive history pair must be canonical"
        }
        require(firstSampleCount > 0L)
        require(secondSampleCount > 0L)
        require(totalVariationMicros == null || totalVariationMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
        if (decision == PredictiveEquivalenceDecision.UNRESOLVED) {
            // Distance may still be available when evidence is adequate but falls between thresholds.
        } else {
            require(totalVariationMicros != null) {
                "MERGE/SPLIT require a measured predictive distance"
            }
        }
        require(policyFingerprint.isNotBlank())
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint = realizationProfileFingerprint,
                firstHistoryFingerprint = firstHistoryFingerprint,
                secondHistoryFingerprint = secondHistoryFingerprint,
                decision = decision,
                totalVariationMicros = totalVariationMicros,
                firstSampleCount = firstSampleCount,
                secondSampleCount = secondSampleCount,
                policyFingerprint = policyFingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            first: EmpiricalPredictiveHistory,
            second: EmpiricalPredictiveHistory,
            decision: PredictiveEquivalenceDecision,
            totalVariationMicros: Long?,
            policyFingerprint: String,
        ): PredictiveEquivalenceAssessment {
            require(first.realizationProfileFingerprint == second.realizationProfileFingerprint) {
                "Finite predictive comparison requires one frozen realization profile"
            }
            require(first.historyFingerprint != second.historyFingerprint) {
                "Finite predictive comparison requires distinct histories"
            }
            val ordered = listOf(first, second).sortedBy { it.historyFingerprint }
            val a = ordered[0]
            val b = ordered[1]
            val fingerprint = expectedFingerprint(
                realizationProfileFingerprint = a.realizationProfileFingerprint,
                firstHistoryFingerprint = a.historyFingerprint,
                secondHistoryFingerprint = b.historyFingerprint,
                decision = decision,
                totalVariationMicros = totalVariationMicros,
                firstSampleCount = a.observations.sampleCount,
                secondSampleCount = b.observations.sampleCount,
                policyFingerprint = policyFingerprint,
            )
            return PredictiveEquivalenceAssessment(
                realizationProfileFingerprint = a.realizationProfileFingerprint,
                firstHistoryFingerprint = a.historyFingerprint,
                secondHistoryFingerprint = b.historyFingerprint,
                decision = decision,
                totalVariationMicros = totalVariationMicros,
                firstSampleCount = a.observations.sampleCount,
                secondSampleCount = b.observations.sampleCount,
                policyFingerprint = policyFingerprint,
                fingerprint = fingerprint,
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            firstHistoryFingerprint: String,
            secondHistoryFingerprint: String,
            decision: PredictiveEquivalenceDecision,
            totalVariationMicros: Long?,
            firstSampleCount: Long,
            secondSampleCount: Long,
            policyFingerprint: String,
        ): String = StableFieldIds.fingerprint(
            "predictive-equivalence-assessment/v1",
            realizationProfileFingerprint,
            firstHistoryFingerprint,
            secondHistoryFingerprint,
            decision.name,
            totalVariationMicros?.toString().orEmpty(),
            firstSampleCount.toString(),
            secondSampleCount.toString(),
            policyFingerprint,
        )
    }
}

/**
 * B520 conservative finite-data classifier.
 *
 * Insufficient evidence never becomes equality. With enough observations the classifier uses an
 * exact integer total-variation distance and preserves the interval between MERGE and SPLIT as
 * UNRESOLVED.
 */
class FinitePredictiveEquivalenceClassifier(
    private val policy: PredictiveEquivalencePolicy =
        PredictiveEquivalencePolicy(),
) {
    fun classify(
        first: EmpiricalPredictiveHistory,
        second: EmpiricalPredictiveHistory,
    ): PredictiveEquivalenceAssessment {
        require(first.realizationProfileFingerprint == second.realizationProfileFingerprint) {
            "Finite predictive comparison requires one frozen realization profile"
        }
        require(first.historyFingerprint != second.historyFingerprint) {
            "Finite predictive comparison requires distinct histories"
        }

        if (
            first.observations.sampleCount < policy.minimumSamplesPerHistory ||
            second.observations.sampleCount < policy.minimumSamplesPerHistory
        ) {
            return PredictiveEquivalenceAssessment.create(
                first = first,
                second = second,
                decision = PredictiveEquivalenceDecision.UNRESOLVED,
                totalVariationMicros = null,
                policyFingerprint = policy.fingerprint(),
            )
        }

        val distance = totalVariationMicros(
            first = first.observations,
            second = second.observations,
        )
        val decision = when {
            distance <= policy.mergeMaximumDistanceMicros ->
                PredictiveEquivalenceDecision.MERGE

            distance >= policy.splitMinimumDistanceMicros ->
                PredictiveEquivalenceDecision.SPLIT

            else ->
                PredictiveEquivalenceDecision.UNRESOLVED
        }
        return PredictiveEquivalenceAssessment.create(
            first = first,
            second = second,
            decision = decision,
            totalVariationMicros = distance,
            policyFingerprint = policy.fingerprint(),
        )
    }
}

internal fun totalVariationMicros(
    first: EmpiricalFutureCounts,
    second: EmpiricalFutureCounts,
): Long {
    val firstTotal = BigInteger.valueOf(first.sampleCount)
    val secondTotal = BigInteger.valueOf(second.sampleCount)
    val outcomeIds = (first.counts.keys + second.counts.keys).toSortedSet()

    val l1Numerator = outcomeIds.fold(BigInteger.ZERO) { acc, outcomeId ->
        val firstCount = BigInteger.valueOf(first.counts[outcomeId] ?: 0L)
        val secondCount = BigInteger.valueOf(second.counts[outcomeId] ?: 0L)
        val difference = firstCount.multiply(secondTotal)
            .subtract(secondCount.multiply(firstTotal))
            .abs()
        acc.add(difference)
    }

    val denominator = firstTotal.multiply(secondTotal).multiply(BigInteger.TWO)
    val scaledNumerator = l1Numerator.multiply(
        BigInteger.valueOf(PREDICTIVE_PROBABILITY_SCALE)
    )

    // Deterministic nearest-integer rounding, half up.
    val rounded = scaledNumerator
        .add(denominator.divide(BigInteger.TWO))
        .divide(denominator)
        .longValueExact()

    require(rounded in 0L..PREDICTIVE_PROBABILITY_SCALE)
    return rounded
}
