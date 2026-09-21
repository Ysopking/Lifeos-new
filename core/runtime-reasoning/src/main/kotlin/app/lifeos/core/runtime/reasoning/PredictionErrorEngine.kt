package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.learning.OutcomeSignal

enum class PredictionErrorState {
    MISSING_OBSERVATION,
    INCOMPLETE_OBSERVATION,
    UNVERIFIED_OBSERVATION,
    WITHIN_EXPECTED_BAND,
    OUTSIDE_EXPECTED_BAND,
}

enum class PredictionErrorDimension {
    COMPLETION,
    CORRECTNESS,
    USEFULNESS,
    POLICY_COMPLIANCE,
}

data class ObservedOutcomeInput(
    val evidenceActionId: String,
    val observationRef: String,
    val observationFingerprint: String,
    val signal: OutcomeSignal,
    val confidence: Double,
    val verified: Boolean,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(observationRef.isNotBlank())
        require(observationFingerprint.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "observed-outcome-input/v1",
        evidenceActionId,
        observationRef,
        observationFingerprint,
        java.lang.Double.toHexString(confidence),
        verified.toString(),
        *signal.canonicalParts().toTypedArray(),
    )
}

data class PredictionErrorComponent(
    val dimension: PredictionErrorDimension,
    val expected: ExpectedOutcomeBand,
    val actual: Double?,
    val signedPointError: Double?,
    val bandDeviation: Double?,
) {
    init {
        if (actual == null) {
            require(signedPointError == null && bandDeviation == null)
        } else {
            require(actual.isFinite() && actual in 0.0..1.0)
            require(signedPointError != null && signedPointError.isFinite())
            require(bandDeviation != null && bandDeviation.isFinite() && bandDeviation >= 0.0)
            require(kotlin.math.abs(signedPointError - (actual - expected.point)) <= 1e-12)
            val expectedDeviation = when {
                actual < expected.lower -> expected.lower - actual
                actual > expected.upper -> actual - expected.upper
                else -> 0.0
            }
            require(kotlin.math.abs(bandDeviation - expectedDeviation) <= 1e-12)
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "prediction-error-component/v1",
        dimension.name,
        expected.fingerprint(),
        actual?.let(java.lang.Double::toHexString) ?: "-",
        signedPointError?.let(java.lang.Double::toHexString) ?: "-",
        bandDeviation?.let(java.lang.Double::toHexString) ?: "-",
    )
}

data class PredictionErrorEntry(
    val evidenceActionId: String,
    val expectationFingerprint: String,
    val observationFingerprint: String?,
    val observationInputFingerprint: String?,
    val state: PredictionErrorState,
    val components: List<PredictionErrorComponent>,
    val meanAbsolutePointError: Double?,
    val meanSignedPointError: Double?,
    val maxBandDeviation: Double?,
    val fingerprint: String,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(expectationFingerprint.isNotBlank())
        require(components.isNotEmpty())
        require(components == components.sortedBy { it.dimension.ordinal })
        require(components.map { it.dimension }.distinct().size == components.size)
        if (observationFingerprint == null) {
            require(observationInputFingerprint == null)
            require(state == PredictionErrorState.MISSING_OBSERVATION)
            require(meanAbsolutePointError == null)
            require(meanSignedPointError == null)
            require(maxBandDeviation == null)
            require(components.all { it.actual == null })
        } else {
            require(!observationFingerprint.isNullOrBlank())
            require(!observationInputFingerprint.isNullOrBlank())
        }
        listOfNotNull(meanAbsolutePointError, maxBandDeviation).forEach {
            require(it.isFinite() && it >= 0.0)
        }
        meanSignedPointError?.let { require(it.isFinite()) }
        require(
            fingerprint == predictionErrorEntryFingerprint(
                evidenceActionId = evidenceActionId,
                expectationFingerprint = expectationFingerprint,
                observationFingerprint = observationFingerprint,
                observationInputFingerprint = observationInputFingerprint,
                state = state,
                components = components,
                meanAbsolutePointError = meanAbsolutePointError,
                meanSignedPointError = meanSignedPointError,
                maxBandDeviation = maxBandDeviation,
            )
        )
    }

    val learningEligible: Boolean
        get() = state == PredictionErrorState.WITHIN_EXPECTED_BAND ||
            state == PredictionErrorState.OUTSIDE_EXPECTED_BAND

    val causalAuthority: Boolean
        get() = false
}

data class PredictionErrorReport(
    val expectationModelFingerprint: String,
    val entries: List<PredictionErrorEntry>,
    val missingObservationActionIds: List<String>,
    val incompleteObservationActionIds: List<String>,
    val unverifiedObservationActionIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(expectationModelFingerprint.isNotBlank())
        require(entries.isNotEmpty())
        require(entries == entries.sortedBy { it.evidenceActionId })
        require(entries.map { it.evidenceActionId }.distinct().size == entries.size)
        require(missingObservationActionIds == missingObservationActionIds.distinct().sorted())
        require(incompleteObservationActionIds == incompleteObservationActionIds.distinct().sorted())
        require(unverifiedObservationActionIds == unverifiedObservationActionIds.distinct().sorted())
        require(
            fingerprint == predictionErrorReportFingerprint(
                expectationModelFingerprint = expectationModelFingerprint,
                entries = entries,
                missingObservationActionIds = missingObservationActionIds,
                incompleteObservationActionIds = incompleteObservationActionIds,
                unverifiedObservationActionIds = unverifiedObservationActionIds,
            )
        )
    }

    val complete: Boolean
        get() = missingObservationActionIds.isEmpty() &&
            incompleteObservationActionIds.isEmpty()

    val verifiedComplete: Boolean
        get() = complete && unverifiedObservationActionIds.isEmpty()

    val causalAuthority: Boolean
        get() = false
}

/**
 * B372 prediction-error boundary.
 *
 * This engine compares B371 expectation bands with observed OutcomeSignal values. It does not
 * reinterpret OutcomeScorer, does not perform adaptation, and does not infer causality. Missing,
 * incomplete, and unverified observations remain explicit terminal states for the current report.
 */
class PredictionErrorEngine {
    fun compare(
        model: OutcomeExpectationModel,
        observations: Collection<ObservedOutcomeInput>,
    ): PredictionErrorReport {
        val byAction = observations.associateBy { it.evidenceActionId }
        require(byAction.size == observations.size) {
            "Each expected action may have at most one observed outcome"
        }

        val expectedIds = model.entries.mapTo(linkedSetOf()) { it.evidenceActionId }
        val unknownIds = byAction.keys - expectedIds
        require(unknownIds.isEmpty()) {
            "Observed outcomes contain unknown evidence actions: " +
                unknownIds.sorted().joinToString(",")
        }

        val entries = model.entries
            .map { expectation ->
                compareEntry(expectation, byAction[expectation.evidenceActionId])
            }
            .sortedBy { it.evidenceActionId }

        val missing = entries
            .filter { it.state == PredictionErrorState.MISSING_OBSERVATION }
            .map { it.evidenceActionId }
            .sorted()
        val incomplete = entries
            .filter { it.state == PredictionErrorState.INCOMPLETE_OBSERVATION }
            .map { it.evidenceActionId }
            .sorted()
        val unverified = entries
            .filter { it.state == PredictionErrorState.UNVERIFIED_OBSERVATION }
            .map { it.evidenceActionId }
            .sorted()

        return PredictionErrorReport(
            expectationModelFingerprint = model.fingerprint,
            entries = entries,
            missingObservationActionIds = missing,
            incompleteObservationActionIds = incomplete,
            unverifiedObservationActionIds = unverified,
            fingerprint = predictionErrorReportFingerprint(
                expectationModelFingerprint = model.fingerprint,
                entries = entries,
                missingObservationActionIds = missing,
                incompleteObservationActionIds = incomplete,
                unverifiedObservationActionIds = unverified,
            ),
        )
    }

    private fun compareEntry(
        expectation: OutcomeExpectationEntry,
        observation: ObservedOutcomeInput?,
    ): PredictionErrorEntry {
        val components = expectedComponents(
            expected = expectation.expected,
            actual = observation?.signal,
        )
        val comparable = components.filter { it.actual != null }
        val allExpectedObserved = comparable.size == components.size

        val state = when {
            observation == null -> PredictionErrorState.MISSING_OBSERVATION
            !allExpectedObserved -> PredictionErrorState.INCOMPLETE_OBSERVATION
            !observation.verified -> PredictionErrorState.UNVERIFIED_OBSERVATION
            components.any { (it.bandDeviation ?: 0.0) > 0.0 } ->
                PredictionErrorState.OUTSIDE_EXPECTED_BAND
            else -> PredictionErrorState.WITHIN_EXPECTED_BAND
        }

        val meanAbsolute = if (comparable.isEmpty()) {
            null
        } else {
            comparable.map { kotlin.math.abs(requireNotNull(it.signedPointError)) }.average()
        }
        val meanSigned = if (comparable.isEmpty()) {
            null
        } else {
            comparable.map { requireNotNull(it.signedPointError) }.average()
        }
        val maxDeviation = comparable
            .mapNotNull { it.bandDeviation }
            .maxOrNull()

        val observationInputFingerprint = observation?.fingerprint()
        return PredictionErrorEntry(
            evidenceActionId = expectation.evidenceActionId,
            expectationFingerprint = expectation.fingerprint,
            observationFingerprint = observation?.observationFingerprint,
            observationInputFingerprint = observationInputFingerprint,
            state = state,
            components = components,
            meanAbsolutePointError = meanAbsolute,
            meanSignedPointError = meanSigned,
            maxBandDeviation = maxDeviation,
            fingerprint = predictionErrorEntryFingerprint(
                evidenceActionId = expectation.evidenceActionId,
                expectationFingerprint = expectation.fingerprint,
                observationFingerprint = observation?.observationFingerprint,
                observationInputFingerprint = observationInputFingerprint,
                state = state,
                components = components,
                meanAbsolutePointError = meanAbsolute,
                meanSignedPointError = meanSigned,
                maxBandDeviation = maxDeviation,
            ),
        )
    }
}

private fun expectedComponents(
    expected: ExpectedOutcomeSignal,
    actual: OutcomeSignal?,
): List<PredictionErrorComponent> = buildList {
    expected.completion?.let {
        add(component(PredictionErrorDimension.COMPLETION, it, actual?.completion))
    }
    expected.correctness?.let {
        add(component(PredictionErrorDimension.CORRECTNESS, it, actual?.correctness))
    }
    expected.usefulness?.let {
        add(component(PredictionErrorDimension.USEFULNESS, it, actual?.usefulness))
    }
    expected.policyCompliance?.let {
        add(component(PredictionErrorDimension.POLICY_COMPLIANCE, it, actual?.policyCompliance))
    }
}.sortedBy { it.dimension.ordinal }

private fun component(
    dimension: PredictionErrorDimension,
    expected: ExpectedOutcomeBand,
    actual: Double?,
): PredictionErrorComponent {
    val signed = actual?.let { it - expected.point }
    val deviation = actual?.let {
        when {
            it < expected.lower -> expected.lower - it
            it > expected.upper -> it - expected.upper
            else -> 0.0
        }
    }
    return PredictionErrorComponent(
        dimension = dimension,
        expected = expected,
        actual = actual,
        signedPointError = signed,
        bandDeviation = deviation,
    )
}

private fun predictionErrorEntryFingerprint(
    evidenceActionId: String,
    expectationFingerprint: String,
    observationFingerprint: String?,
    observationInputFingerprint: String?,
    state: PredictionErrorState,
    components: List<PredictionErrorComponent>,
    meanAbsolutePointError: Double?,
    meanSignedPointError: Double?,
    maxBandDeviation: Double?,
): String = StableFieldIds.fingerprint(
    "prediction-error-entry/v1",
    evidenceActionId,
    expectationFingerprint,
    observationFingerprint ?: "-",
    observationInputFingerprint ?: "-",
    state.name,
    meanAbsolutePointError?.let(java.lang.Double::toHexString) ?: "-",
    meanSignedPointError?.let(java.lang.Double::toHexString) ?: "-",
    maxBandDeviation?.let(java.lang.Double::toHexString) ?: "-",
    *components.sortedBy { it.dimension.ordinal }
        .map { "component:" + it.fingerprint() }
        .toTypedArray(),
)

private fun predictionErrorReportFingerprint(
    expectationModelFingerprint: String,
    entries: List<PredictionErrorEntry>,
    missingObservationActionIds: List<String>,
    incompleteObservationActionIds: List<String>,
    unverifiedObservationActionIds: List<String>,
): String = StableFieldIds.fingerprint(
    "prediction-error-report/v1",
    expectationModelFingerprint,
    *entries.sortedBy { it.evidenceActionId }
        .map { "entry:" + it.fingerprint }
        .toTypedArray(),
    *missingObservationActionIds.sorted()
        .map { "missing:" + it }
        .toTypedArray(),
    *incompleteObservationActionIds.sorted()
        .map { "incomplete:" + it }
        .toTypedArray(),
    *unverifiedObservationActionIds.sorted()
        .map { "unverified:" + it }
        .toTypedArray(),
)
