package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.learning.OutcomeSignal

data class ExpectedOutcomeBand(
    val lower: Double,
    val point: Double,
    val upper: Double,
) {
    init {
        require(lower.isFinite() && point.isFinite() && upper.isFinite())
        require(lower in 0.0..1.0 && point in 0.0..1.0 && upper in 0.0..1.0)
        require(lower <= point && point <= upper) {
            "Expected outcome band must satisfy lower <= point <= upper"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "expected-outcome-band/v1",
        java.lang.Double.toHexString(lower),
        java.lang.Double.toHexString(point),
        java.lang.Double.toHexString(upper),
    )
}

data class ExpectedOutcomeSignal(
    val completion: ExpectedOutcomeBand? = null,
    val correctness: ExpectedOutcomeBand? = null,
    val usefulness: ExpectedOutcomeBand? = null,
    val policyCompliance: ExpectedOutcomeBand? = null,
) {
    init {
        require(
            completion != null ||
                correctness != null ||
                usefulness != null ||
                policyCompliance != null
        ) { "Outcome expectation requires at least one expected signal" }
    }

    fun pointSignal(): OutcomeSignal = OutcomeSignal(
        completion = completion?.point,
        correctness = correctness?.point,
        usefulness = usefulness?.point,
        policyCompliance = policyCompliance?.point,
    )

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "expected-outcome-signal/v1",
        "completion:" + (completion?.fingerprint() ?: "-"),
        "correctness:" + (correctness?.fingerprint() ?: "-"),
        "usefulness:" + (usefulness?.fingerprint() ?: "-"),
        "policy:" + (policyCompliance?.fingerprint() ?: "-"),
    )
}

data class OutcomeExpectationInput(
    val evidenceActionId: String,
    val expected: ExpectedOutcomeSignal,
    val confidence: Double,
    val rationale: String,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(rationale.isNotBlank())
    }
}

data class OutcomeExpectationEntry(
    val evidenceActionId: String,
    val requestFingerprint: String,
    val expected: ExpectedOutcomeSignal,
    val confidence: Double,
    val rationale: String,
    val fingerprint: String,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(rationale.isNotBlank())
        require(
            fingerprint == entryFingerprint(
                evidenceActionId = evidenceActionId,
                requestFingerprint = requestFingerprint,
                expected = expected,
                confidence = confidence,
                rationale = rationale,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false
}

data class OutcomeExpectationModel(
    val sourceCycleId: String,
    val reasoningSearchFingerprint: String,
    val counterfactualBatchFingerprint: String,
    val experimentPlanFingerprint: String,
    val entries: List<OutcomeExpectationEntry>,
    val fingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(reasoningSearchFingerprint.isNotBlank())
        require(counterfactualBatchFingerprint.isNotBlank())
        require(experimentPlanFingerprint.isNotBlank())
        require(entries.isNotEmpty())
        require(entries == entries.sortedBy { it.evidenceActionId })
        require(entries.map { it.evidenceActionId }.distinct().size == entries.size)
        require(
            fingerprint == modelFingerprint(
                sourceCycleId = sourceCycleId,
                reasoningSearchFingerprint = reasoningSearchFingerprint,
                counterfactualBatchFingerprint = counterfactualBatchFingerprint,
                experimentPlanFingerprint = experimentPlanFingerprint,
                entries = entries,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false
}

/**
 * B371 expectation boundary.
 *
 * Expectations are predictive contracts over already admitted B370 experiment actions.
 * They are not evidence, do not prove success, and do not authorize execution. The four
 * signal dimensions intentionally mirror OutcomeSignal so B372 can compare expectation
 * bands to actual outcome evidence without inventing a parallel outcome vocabulary.
 */
class OutcomeExpectationModelBuilder {
    fun build(
        plan: ExperimentPlan,
        expectations: Collection<OutcomeExpectationInput>,
    ): OutcomeExpectationModel {
        require(plan.items.isNotEmpty()) {
            "Outcome expectations require at least one admitted experiment"
        }
        require(expectations.isNotEmpty()) {
            "Outcome expectations require explicit expected signals"
        }

        val inputByAction = expectations.associateBy { it.evidenceActionId }
        require(inputByAction.size == expectations.size) {
            "Each experiment action may have at most one outcome expectation"
        }

        val plannedIds = plan.items
            .mapTo(linkedSetOf()) { it.evidenceAction.id }
        require(inputByAction.keys == plannedIds) {
            val missing = plannedIds - inputByAction.keys
            val unknown = inputByAction.keys - plannedIds
            "Outcome expectations must cover admitted experiment actions exactly; " +
                "missing=" + missing.sorted().joinToString(",") +
                ";unknown=" + unknown.sorted().joinToString(",")
        }

        val entries = plan.items
            .map { item ->
                val input = inputByAction.getValue(item.evidenceAction.id)
                OutcomeExpectationEntry(
                    evidenceActionId = item.evidenceAction.id,
                    requestFingerprint = item.requestFingerprint,
                    expected = input.expected,
                    confidence = input.confidence,
                    rationale = input.rationale,
                    fingerprint = entryFingerprint(
                        evidenceActionId = item.evidenceAction.id,
                        requestFingerprint = item.requestFingerprint,
                        expected = input.expected,
                        confidence = input.confidence,
                        rationale = input.rationale,
                    ),
                )
            }
            .sortedBy { it.evidenceActionId }

        return OutcomeExpectationModel(
            sourceCycleId = plan.sourceCycleId,
            reasoningSearchFingerprint = plan.reasoningSearchFingerprint,
            counterfactualBatchFingerprint = plan.counterfactualBatchFingerprint,
            experimentPlanFingerprint = plan.fingerprint,
            entries = entries,
            fingerprint = modelFingerprint(
                sourceCycleId = plan.sourceCycleId,
                reasoningSearchFingerprint = plan.reasoningSearchFingerprint,
                counterfactualBatchFingerprint = plan.counterfactualBatchFingerprint,
                experimentPlanFingerprint = plan.fingerprint,
                entries = entries,
            ),
        )
    }
}

private fun entryFingerprint(
    evidenceActionId: String,
    requestFingerprint: String,
    expected: ExpectedOutcomeSignal,
    confidence: Double,
    rationale: String,
): String = StableFieldIds.fingerprint(
    "outcome-expectation-entry/v1",
    evidenceActionId,
    requestFingerprint,
    expected.fingerprint(),
    java.lang.Double.toHexString(confidence),
    rationale,
)

private fun modelFingerprint(
    sourceCycleId: String,
    reasoningSearchFingerprint: String,
    counterfactualBatchFingerprint: String,
    experimentPlanFingerprint: String,
    entries: List<OutcomeExpectationEntry>,
): String = StableFieldIds.fingerprint(
    "outcome-expectation-model/v1",
    sourceCycleId,
    reasoningSearchFingerprint,
    counterfactualBatchFingerprint,
    experimentPlanFingerprint,
    *entries.sortedBy { it.evidenceActionId }
        .map { "entry:" + it.fingerprint }
        .toTypedArray(),
)
