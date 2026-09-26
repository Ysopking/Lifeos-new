package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class MultiscaleClosureStatus {
    EXACT,
    APPROXIMATE,
    NOT_CLOSED,
    UNRESOLVED,
}

data class MultiscaleTransitionSample(
    val realizationProfileFingerprint: String,
    val microStateFingerprint: String,
    val projectedMacroStateFingerprint: String,
    val projectedMicroSuccessorLaw: DiscreteFutureLaw,
    val macroSuccessorLaw: DiscreteFutureLaw,
    val evidenceFingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(microStateFingerprint.isNotBlank())
        require(projectedMacroStateFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "multiscale-transition-sample/v1",
        realizationProfileFingerprint,
        microStateFingerprint,
        projectedMacroStateFingerprint,
        projectedMicroSuccessorLaw.fingerprint,
        macroSuccessorLaw.fingerprint,
        evidenceFingerprint,
    )
}

data class MultiscaleClosurePolicy(
    val distanceMetricId: String = "total-variation-micros/v1",
    val aggregationRuleFingerprint: String,
    val maximumApproximationErrorMicros: Long,
) {
    init {
        require(distanceMetricId.isNotBlank())
        require(aggregationRuleFingerprint.isNotBlank())
        require(maximumApproximationErrorMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "multiscale-closure-policy/v1",
        distanceMetricId,
        aggregationRuleFingerprint,
        maximumApproximationErrorMicros.toString(),
    )
}

data class MultiscaleClosureResult(
    val realizationProfileFingerprint: String,
    val status: MultiscaleClosureStatus,
    val maximumObservedErrorMicros: Long?,
    val policyFingerprint: String,
    val evidenceFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(maximumObservedErrorMicros == null ||
            maximumObservedErrorMicros in 0L..PREDICTIVE_PROBABILITY_SCALE)
        require(policyFingerprint.isNotBlank())
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        if (status == MultiscaleClosureStatus.UNRESOLVED) {
            require(maximumObservedErrorMicros == null)
        } else {
            require(maximumObservedErrorMicros != null)
        }
    }

    val truthAuthority: Boolean
        get() = false
}

/**
 * B524 M8 closure evaluator.
 *
 * It compares the projected micro-successor law with the macro-successor law using an explicitly
 * frozen metric and aggregation-rule fingerprint. Approximation is never silent.
 */
class MultiscaleClosureEvaluator(
    private val policy: MultiscaleClosurePolicy,
) {
    fun evaluate(
        samples: Collection<MultiscaleTransitionSample>,
    ): MultiscaleClosureResult {
        if (samples.isEmpty()) {
            return result(
                profile = "unresolved:no-samples",
                status = MultiscaleClosureStatus.UNRESOLVED,
                maximumError = null,
                evidence = emptyList(),
            )
        }
        val canonical = samples.distinctBy { it.fingerprint() }.sortedBy { it.fingerprint() }
        val profile = canonical.first().realizationProfileFingerprint
        require(canonical.all { it.realizationProfileFingerprint == profile }) {
            "Multiscale closure samples must use one frozen realization profile"
        }

        val errors = canonical.map {
            totalVariationMicros(
                it.projectedMicroSuccessorLaw,
                it.macroSuccessorLaw,
            )
        }
        val maximum = errors.maxOrNull() ?: 0L
        val status = when {
            maximum == 0L -> MultiscaleClosureStatus.EXACT
            maximum <= policy.maximumApproximationErrorMicros ->
                MultiscaleClosureStatus.APPROXIMATE
            else -> MultiscaleClosureStatus.NOT_CLOSED
        }
        return result(
            profile = profile,
            status = status,
            maximumError = maximum,
            evidence = canonical.map { it.evidenceFingerprint },
        )
    }

    private fun result(
        profile: String,
        status: MultiscaleClosureStatus,
        maximumError: Long?,
        evidence: Collection<String>,
    ): MultiscaleClosureResult {
        val canonicalEvidence = evidence.distinct().sorted()
        val fingerprint = StableFieldIds.fingerprint(
            "multiscale-closure-result/v1",
            profile,
            status.name,
            maximumError?.toString().orEmpty(),
            policy.fingerprint(),
            *canonicalEvidence.map { "evidence:$it" }.toTypedArray(),
        )
        return MultiscaleClosureResult(
            realizationProfileFingerprint = profile,
            status = status,
            maximumObservedErrorMicros = maximumError,
            policyFingerprint = policy.fingerprint(),
            evidenceFingerprints = canonicalEvidence,
            fingerprint = fingerprint,
        )
    }
}

internal fun totalVariationMicros(
    first: DiscreteFutureLaw,
    second: DiscreteFutureLaw,
): Long {
    val keys = (first.probabilityMicros.keys + second.probabilityMicros.keys).toSortedSet()
    val l1 = keys.sumOf { key ->
        kotlin.math.abs(
            (first.probabilityMicros[key] ?: 0L) -
                (second.probabilityMicros[key] ?: 0L)
        )
    }
    require(l1 % 2L == 0L) {
        "Fixed-scale total-variation distance must divide evenly by two"
    }
    return l1 / 2L
}
