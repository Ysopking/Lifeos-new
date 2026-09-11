package app.lifeos.core.runtime.learning

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import java.time.Instant

@JvmInline
value class OutcomePredictionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid outcome prediction id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid outcome prediction digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "outcome-prediction:"
    }
}

data class OutcomeExpectedHypothesis(
    val hypothesisId: HypothesisId,
    val confidenceBand: ConvergenceConfidenceBand,
)

data class OutcomePrediction(
    val id: OutcomePredictionId,
    val actionId: String,
    val decisionId: ConvergenceDecisionId,
    val expectedHypotheses: List<OutcomeExpectedHypothesis>,
    val providerIds: List<String>,
    val fieldSnapshotFingerprints: List<String>,
    val thoughtGraphWorkingSetFingerprint: String,
    val decisionPolicyFingerprint: String,
    val createdAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "Outcome prediction action id must not be blank" }
        require(expectedHypotheses.isNotEmpty()) { "Outcome prediction requires an expected hypothesis" }
        require(expectedHypotheses.map { it.hypothesisId }.distinct().size == expectedHypotheses.size) {
            "Outcome prediction hypotheses must be unique"
        }
        require(providerIds.none { it.isBlank() } && providerIds.distinct().size == providerIds.size) {
            "Outcome prediction providers must be unique and non-blank"
        }
        require(fieldSnapshotFingerprints.isNotEmpty()) { "Outcome prediction requires field lineage" }
        require(fieldSnapshotFingerprints.none { it.isBlank() }) { "Field snapshot fingerprint must not be blank" }
        require(fieldSnapshotFingerprints.distinct().size == fieldSnapshotFingerprints.size) {
            "Field snapshot fingerprints must be unique"
        }
        require(thoughtGraphWorkingSetFingerprint.isNotBlank()) {
            "Outcome prediction requires ThoughtGraph working-set lineage"
        }
        require(decisionPolicyFingerprint.isNotBlank()) {
            "Outcome prediction requires decision policy lineage"
        }
        require(id == expectedId()) { "Outcome prediction id/content mismatch" }
    }

    fun contentFingerprint(): String = fingerprint(
        actionId = actionId,
        decisionId = decisionId,
        expectedHypotheses = expectedHypotheses,
        providerIds = providerIds,
        fieldSnapshotFingerprints = fieldSnapshotFingerprints,
        thoughtGraphWorkingSetFingerprint = thoughtGraphWorkingSetFingerprint,
        decisionPolicyFingerprint = decisionPolicyFingerprint,
        createdAt = createdAt,
    )

    private fun expectedId(): OutcomePredictionId =
        OutcomePredictionId("${OutcomePredictionId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            actionId: String,
            decisionId: ConvergenceDecisionId,
            expectedHypotheses: List<OutcomeExpectedHypothesis>,
            providerIds: List<String>,
            fieldSnapshotFingerprints: List<String>,
            thoughtGraphWorkingSetFingerprint: String,
            decisionPolicyFingerprint: String,
            createdAt: Instant,
        ): OutcomePrediction {
            val canonicalHypotheses = expectedHypotheses
                .distinctBy { it.hypothesisId }
                .sortedBy { it.hypothesisId.value }
            require(canonicalHypotheses.size == expectedHypotheses.size) {
                "Outcome prediction hypotheses must be unique"
            }
            val canonicalProviders = providerIds.distinct().sorted()
            require(canonicalProviders.size == providerIds.size) {
                "Outcome prediction providers must be unique"
            }
            val canonicalFieldFingerprints = fieldSnapshotFingerprints.distinct().sorted()
            require(canonicalFieldFingerprints.size == fieldSnapshotFingerprints.size) {
                "Field snapshot fingerprints must be unique"
            }
            val fingerprint = fingerprint(
                actionId = actionId,
                decisionId = decisionId,
                expectedHypotheses = canonicalHypotheses,
                providerIds = canonicalProviders,
                fieldSnapshotFingerprints = canonicalFieldFingerprints,
                thoughtGraphWorkingSetFingerprint = thoughtGraphWorkingSetFingerprint,
                decisionPolicyFingerprint = decisionPolicyFingerprint,
                createdAt = createdAt,
            )
            return OutcomePrediction(
                id = OutcomePredictionId("${OutcomePredictionId.PREFIX}$fingerprint"),
                actionId = actionId,
                decisionId = decisionId,
                expectedHypotheses = canonicalHypotheses,
                providerIds = canonicalProviders,
                fieldSnapshotFingerprints = canonicalFieldFingerprints,
                thoughtGraphWorkingSetFingerprint = thoughtGraphWorkingSetFingerprint,
                decisionPolicyFingerprint = decisionPolicyFingerprint,
                createdAt = createdAt,
            )
        }

        private fun fingerprint(
            actionId: String,
            decisionId: ConvergenceDecisionId,
            expectedHypotheses: List<OutcomeExpectedHypothesis>,
            providerIds: List<String>,
            fieldSnapshotFingerprints: List<String>,
            thoughtGraphWorkingSetFingerprint: String,
            decisionPolicyFingerprint: String,
            createdAt: Instant,
        ): String = StableFieldIds.fingerprint(
            "outcome-prediction/v1",
            actionId,
            decisionId.value,
            thoughtGraphWorkingSetFingerprint,
            decisionPolicyFingerprint,
            createdAt.toString(),
            *expectedHypotheses.sortedBy { it.hypothesisId.value }.flatMap { expected ->
                listOf(
                    "hypothesis:${expected.hypothesisId.value}",
                    "band-lower:${java.lang.Double.toHexString(expected.confidenceBand.lower)}",
                    "band-point:${java.lang.Double.toHexString(expected.confidenceBand.point)}",
                    "band-upper:${java.lang.Double.toHexString(expected.confidenceBand.upper)}",
                )
            }.toTypedArray(),
            *providerIds.sorted().map { "provider:$it" }.toTypedArray(),
            *fieldSnapshotFingerprints.sorted().map { "field:$it" }.toTypedArray(),
        )
    }
}
