package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class IdentifiabilityStatus {
    OBSERVATIONALLY_DISTINCT,
    INTERVENTION_SEPARABLE,
    CURRENTLY_INSEPARABLE,
    UNRESOLVED,
}

data class PredictiveCandidateSignature private constructor(
    val realizationProfileFingerprint: String,
    val candidateId: String,
    val observationalLawFingerprint: String,
    val interventionalLawFingerprints: Map<String, String>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(candidateId.isNotBlank())
        require(observationalLawFingerprint.isNotBlank())
        require(interventionalLawFingerprints.keys.none { it.isBlank() })
        require(interventionalLawFingerprints.values.none { it.isBlank() })
        require(
            interventionalLawFingerprints.keys.toList() ==
                interventionalLawFingerprints.keys.sorted()
        ) {
            "Interventional predictive laws must be canonical"
        }
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint = realizationProfileFingerprint,
                candidateId = candidateId,
                observationalLawFingerprint = observationalLawFingerprint,
                interventionalLawFingerprints = interventionalLawFingerprints,
            )
        )
    }

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            candidateId: String,
            observationalLawFingerprint: String,
            interventionalLawFingerprints: Map<String, String> = emptyMap(),
        ): PredictiveCandidateSignature {
            require(realizationProfileFingerprint.isNotBlank())
            require(candidateId.isNotBlank())
            require(observationalLawFingerprint.isNotBlank())
            require(interventionalLawFingerprints.keys.none { it.isBlank() })
            require(interventionalLawFingerprints.values.none { it.isBlank() })
            val interventions = interventionalLawFingerprints.toSortedMap()
            return PredictiveCandidateSignature(
                realizationProfileFingerprint = realizationProfileFingerprint,
                candidateId = candidateId,
                observationalLawFingerprint = observationalLawFingerprint,
                interventionalLawFingerprints = interventions,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint = realizationProfileFingerprint,
                    candidateId = candidateId,
                    observationalLawFingerprint = observationalLawFingerprint,
                    interventionalLawFingerprints = interventions,
                ),
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            candidateId: String,
            observationalLawFingerprint: String,
            interventionalLawFingerprints: Map<String, String>,
        ): String = StableFieldIds.fingerprint(
            "predictive-candidate-signature/v1",
            realizationProfileFingerprint,
            candidateId,
            observationalLawFingerprint,
            *interventionalLawFingerprints.map { (interventionId, lawFingerprint) ->
                "$interventionId:$lawFingerprint"
            }.toTypedArray(),
        )
    }
}

data class IdentifiabilityAssessment(
    val realizationProfileFingerprint: String,
    val candidateIds: List<String>,
    val status: IdentifiabilityStatus,
    val sharedInterventionIds: List<String>,
    val discriminatingInterventionIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(candidateIds.size >= 2)
        require(candidateIds == candidateIds.distinct().sorted())
        require(sharedInterventionIds == sharedInterventionIds.distinct().sorted())
        require(
            discriminatingInterventionIds ==
                discriminatingInterventionIds.distinct().sorted()
        )
        require(discriminatingInterventionIds.all { it in sharedInterventionIds })
        when (status) {
            IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT ->
                require(discriminatingInterventionIds.isEmpty())

            IdentifiabilityStatus.INTERVENTION_SEPARABLE ->
                require(discriminatingInterventionIds.isNotEmpty())

            IdentifiabilityStatus.CURRENTLY_INSEPARABLE ->
                require(
                    sharedInterventionIds.isNotEmpty() &&
                        discriminatingInterventionIds.isEmpty()
                )

            IdentifiabilityStatus.UNRESOLVED ->
                require(discriminatingInterventionIds.isEmpty())
        }
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint = realizationProfileFingerprint,
                candidateIds = candidateIds,
                status = status,
                sharedInterventionIds = sharedInterventionIds,
                discriminatingInterventionIds = discriminatingInterventionIds,
            )
        )
    }

    val causalAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            candidateIds: Collection<String>,
            status: IdentifiabilityStatus,
            sharedInterventionIds: Collection<String>,
            discriminatingInterventionIds: Collection<String>,
        ): IdentifiabilityAssessment {
            val candidates = candidateIds.distinct().sorted()
            val shared = sharedInterventionIds.distinct().sorted()
            val discriminating = discriminatingInterventionIds.distinct().sorted()
            require(candidates.size >= 2)
            return IdentifiabilityAssessment(
                realizationProfileFingerprint = realizationProfileFingerprint,
                candidateIds = candidates,
                status = status,
                sharedInterventionIds = shared,
                discriminatingInterventionIds = discriminating,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    candidates,
                    status,
                    shared,
                    discriminating,
                ),
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            candidateIds: List<String>,
            status: IdentifiabilityStatus,
            sharedInterventionIds: List<String>,
            discriminatingInterventionIds: List<String>,
        ): String = StableFieldIds.fingerprint(
            "predictive-identifiability-assessment/v1",
            realizationProfileFingerprint,
            status.name,
            *candidateIds.map { "candidate:$it" }.toTypedArray(),
            *sharedInterventionIds.map { "shared:$it" }.toTypedArray(),
            *discriminatingInterventionIds.map { "discriminating:$it" }.toTypedArray(),
        )
    }
}

/**
 * B521 M5 analyzer.
 *
 * Distinct observational laws are already separable. If observational laws match, only
 * interventions represented for every candidate may be used as discriminators. Partial
 * intervention coverage remains UNRESOLVED rather than being mistaken for equivalence.
 */
class PredictiveIdentifiabilityAnalyzer {
    fun assess(
        candidates: Collection<PredictiveCandidateSignature>,
    ): IdentifiabilityAssessment {
        require(candidates.size >= 2) {
            "Identifiability analysis requires at least two candidates"
        }
        val canonical = candidates.sortedBy { it.candidateId }
        require(canonical.map { it.candidateId }.distinct().size == canonical.size) {
            "Identifiability candidates must have unique ids"
        }
        val profile = canonical.first().realizationProfileFingerprint
        require(canonical.all { it.realizationProfileFingerprint == profile }) {
            "Identifiability candidates must use one frozen realization profile"
        }

        if (
            canonical.map(PredictiveCandidateSignature::observationalLawFingerprint)
                .distinct()
                .size > 1
        ) {
            return IdentifiabilityAssessment.create(
                realizationProfileFingerprint = profile,
                candidateIds = canonical.map(PredictiveCandidateSignature::candidateId),
                status = IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT,
                sharedInterventionIds = emptyList(),
                discriminatingInterventionIds = emptyList(),
            )
        }

        val interventionSets = canonical.map {
            it.interventionalLawFingerprints.keys.toSet()
        }
        val sharedInterventions = interventionSets
            .reduce { acc, ids -> acc intersect ids }
            .sorted()

        if (sharedInterventions.isEmpty()) {
            return IdentifiabilityAssessment.create(
                realizationProfileFingerprint = profile,
                candidateIds = canonical.map(PredictiveCandidateSignature::candidateId),
                status = IdentifiabilityStatus.UNRESOLVED,
                sharedInterventionIds = emptyList(),
                discriminatingInterventionIds = emptyList(),
            )
        }

        val discriminating = sharedInterventions.filter { interventionId ->
            canonical
                .map { it.interventionalLawFingerprints.getValue(interventionId) }
                .distinct()
                .size > 1
        }

        val status = if (discriminating.isNotEmpty()) {
            IdentifiabilityStatus.INTERVENTION_SEPARABLE
        } else {
            IdentifiabilityStatus.CURRENTLY_INSEPARABLE
        }
        return IdentifiabilityAssessment.create(
            realizationProfileFingerprint = profile,
            candidateIds = canonical.map(PredictiveCandidateSignature::candidateId),
            status = status,
            sharedInterventionIds = sharedInterventions,
            discriminatingInterventionIds = discriminating,
        )
    }
}
