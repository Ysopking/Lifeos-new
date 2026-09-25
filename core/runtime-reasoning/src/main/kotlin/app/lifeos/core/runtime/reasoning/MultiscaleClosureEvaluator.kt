package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import kotlin.math.abs

enum class MultiscaleClosureStatus {
    EXACT,
    APPROXIMATE,
    NOT_CLOSED,
    UNRESOLVED,
}

data class MultiscaleClosurePolicy(
    val distanceMetricId: String = TOTAL_VARIATION_METRIC_ID,
    val aggregationRuleFingerprint: String,
    val maximumApproximationErrorMicros: Long,
    val minimumComparableSamples: Int = 2,
) {
    init {
        require(distanceMetricId == TOTAL_VARIATION_METRIC_ID) {
            "B524 currently supports only deterministic total-variation distance"
        }
        require(aggregationRuleFingerprint.isNotBlank())
        require(maximumApproximationErrorMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
        require(minimumComparableSamples in 2..10_000)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "multiscale-closure-policy/v1",
        distanceMetricId,
        aggregationRuleFingerprint,
        maximumApproximationErrorMicros.toString(),
        minimumComparableSamples.toString(),
    )

    companion object {
        const val TOTAL_VARIATION_METRIC_ID = "total-variation-micros/v1"
    }
}

data class MultiscaleTransitionSample private constructor(
    val realizationProfileFingerprint: String,
    val microStateFingerprint: String,
    val projectedMacroStateFingerprint: String,
    val projectedMicroSuccessorLaw: DiscreteFutureLaw,
    val macroSuccessorLaw: DiscreteFutureLaw,
    val evidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(microStateFingerprint.isNotBlank())
        require(projectedMacroStateFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint,
                microStateFingerprint,
                projectedMacroStateFingerprint,
                projectedMicroSuccessorLaw,
                macroSuccessorLaw,
                evidenceFingerprint,
            )
        )
    }

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            microStateFingerprint: String,
            projectedMacroStateFingerprint: String,
            projectedMicroSuccessorLaw: DiscreteFutureLaw,
            macroSuccessorLaw: DiscreteFutureLaw,
            evidenceFingerprint: String,
        ): MultiscaleTransitionSample {
            require(realizationProfileFingerprint.isNotBlank())
            require(microStateFingerprint.isNotBlank())
            require(projectedMacroStateFingerprint.isNotBlank())
            require(evidenceFingerprint.isNotBlank())
            return MultiscaleTransitionSample(
                realizationProfileFingerprint = realizationProfileFingerprint,
                microStateFingerprint = microStateFingerprint,
                projectedMacroStateFingerprint = projectedMacroStateFingerprint,
                projectedMicroSuccessorLaw = projectedMicroSuccessorLaw,
                macroSuccessorLaw = macroSuccessorLaw,
                evidenceFingerprint = evidenceFingerprint,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    microStateFingerprint,
                    projectedMacroStateFingerprint,
                    projectedMicroSuccessorLaw,
                    macroSuccessorLaw,
                    evidenceFingerprint,
                ),
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            microStateFingerprint: String,
            projectedMacroStateFingerprint: String,
            projectedMicroSuccessorLaw: DiscreteFutureLaw,
            macroSuccessorLaw: DiscreteFutureLaw,
            evidenceFingerprint: String,
        ): String = StableFieldIds.fingerprint(
            "multiscale-transition-sample/v1",
            realizationProfileFingerprint,
            microStateFingerprint,
            projectedMacroStateFingerprint,
            projectedMicroSuccessorLaw.fingerprint,
            macroSuccessorLaw.fingerprint,
            evidenceFingerprint,
        )
    }
}

data class MultiscaleClosureAssessment(
    val realizationProfileFingerprint: String,
    val policyFingerprint: String,
    val status: MultiscaleClosureStatus,
    val comparedSampleFingerprints: List<String>,
    val maximumObservedErrorMicros: Long?,
    val failingSampleFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(
            comparedSampleFingerprints ==
                comparedSampleFingerprints.distinct().sorted()
        )
        require(
            failingSampleFingerprints ==
                failingSampleFingerprints.distinct().sorted()
        )
        require(
            maximumObservedErrorMicros == null ||
                maximumObservedErrorMicros in 0L..PREDICTIVE_PROBABILITY_SCALE
        )
        when (status) {
            MultiscaleClosureStatus.EXACT -> {
                require(comparedSampleFingerprints.isNotEmpty())
                require(maximumObservedErrorMicros == 0L)
                require(failingSampleFingerprints.isEmpty())
            }
            MultiscaleClosureStatus.APPROXIMATE -> {
                require(comparedSampleFingerprints.isNotEmpty())
                require(requireNotNull(maximumObservedErrorMicros) > 0L)
                require(failingSampleFingerprints.isEmpty())
            }
            MultiscaleClosureStatus.NOT_CLOSED -> {
                require(failingSampleFingerprints.isNotEmpty())
                require(maximumObservedErrorMicros != null)
            }
            MultiscaleClosureStatus.UNRESOLVED -> {
                require(failingSampleFingerprints.isEmpty())
            }
        }
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint,
                policyFingerprint,
                status,
                comparedSampleFingerprints,
                maximumObservedErrorMicros,
                failingSampleFingerprints,
            )
        )
    }

    val autonomousMacroDynamicsEstablished: Boolean
        get() = status == MultiscaleClosureStatus.EXACT ||
            status == MultiscaleClosureStatus.APPROXIMATE

    val truthAuthority: Boolean
        get() = false

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            policyFingerprint: String,
            status: MultiscaleClosureStatus,
            comparedSampleFingerprints: Collection<String>,
            maximumObservedErrorMicros: Long?,
            failingSampleFingerprints: Collection<String>,
        ): MultiscaleClosureAssessment {
            val compared = comparedSampleFingerprints.distinct().sorted()
            val failing = failingSampleFingerprints.distinct().sorted()
            return MultiscaleClosureAssessment(
                realizationProfileFingerprint = realizationProfileFingerprint,
                policyFingerprint = policyFingerprint,
                status = status,
                comparedSampleFingerprints = compared,
                maximumObservedErrorMicros = maximumObservedErrorMicros,
                failingSampleFingerprints = failing,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    policyFingerprint,
                    status,
                    compared,
                    maximumObservedErrorMicros,
                    failing,
                ),
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            policyFingerprint: String,
            status: MultiscaleClosureStatus,
            comparedSampleFingerprints: List<String>,
            maximumObservedErrorMicros: Long?,
            failingSampleFingerprints: List<String>,
        ): String = StableFieldIds.fingerprint(
            "multiscale-closure-assessment/v1",
            realizationProfileFingerprint,
            policyFingerprint,
            status.name,
            maximumObservedErrorMicros?.toString().orEmpty(),
            *comparedSampleFingerprints.map { "sample:$it" }.toTypedArray(),
            *failingSampleFingerprints.map { "failing:$it" }.toTypedArray(),
        )
    }
}

/**
 * B524 M8 evaluator for the commuting condition between projected micro dynamics and an explicit
 * macro dynamics. Empty or undersampled evidence remains UNRESOLVED.
 */
class MultiscaleClosureEvaluator(
    private val policy: MultiscaleClosurePolicy,
) {
    fun evaluate(
        realizationProfileFingerprint: String,
        samples: Collection<MultiscaleTransitionSample>,
    ): MultiscaleClosureAssessment {
        require(realizationProfileFingerprint.isNotBlank())
        val canonical = samples
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        require(canonical.size == samples.size) {
            "Duplicate multiscale transition samples are not allowed"
        }
        require(
            canonical.all {
                it.realizationProfileFingerprint == realizationProfileFingerprint
            }
        ) {
            "Multiscale closure samples must use the requested frozen realization profile"
        }

        if (canonical.size < policy.minimumComparableSamples) {
            return MultiscaleClosureAssessment.create(
                realizationProfileFingerprint = realizationProfileFingerprint,
                policyFingerprint = policy.fingerprint(),
                status = MultiscaleClosureStatus.UNRESOLVED,
                comparedSampleFingerprints = canonical.map { it.fingerprint },
                maximumObservedErrorMicros = canonical
                    .map(::sampleErrorMicros)
                    .maxOrNull(),
                failingSampleFingerprints = emptyList(),
            )
        }

        val errors = canonical.associateWith(::sampleErrorMicros)
        val maxError = errors.values.maxOrNull() ?: 0L
        val failing = errors
            .filterValues { it > policy.maximumApproximationErrorMicros }
            .keys
            .map { it.fingerprint }
            .sorted()

        val status = when {
            failing.isNotEmpty() -> MultiscaleClosureStatus.NOT_CLOSED
            maxError == 0L -> MultiscaleClosureStatus.EXACT
            else -> MultiscaleClosureStatus.APPROXIMATE
        }

        return MultiscaleClosureAssessment.create(
            realizationProfileFingerprint = realizationProfileFingerprint,
            policyFingerprint = policy.fingerprint(),
            status = status,
            comparedSampleFingerprints = canonical.map { it.fingerprint },
            maximumObservedErrorMicros = maxError,
            failingSampleFingerprints = failing,
        )
    }

    private fun sampleErrorMicros(
        sample: MultiscaleTransitionSample,
    ): Long = totalVariationMicros(
        sample.projectedMicroSuccessorLaw,
        sample.macroSuccessorLaw,
    )
}

internal fun totalVariationMicros(
    first: DiscreteFutureLaw,
    second: DiscreteFutureLaw,
): Long {
    val outcomeIds = (first.probabilityMicros.keys + second.probabilityMicros.keys)
        .toSortedSet()
    val l1 = outcomeIds.sumOf { outcomeId ->
        abs(
            (first.probabilityMicros[outcomeId] ?: 0L) -
                (second.probabilityMicros[outcomeId] ?: 0L)
        )
    }
    require(l1 % 2L == 0L) {
        "Discrete future-law total variation must be exactly representable on the fixed scale"
    }
    return l1 / 2L
}
