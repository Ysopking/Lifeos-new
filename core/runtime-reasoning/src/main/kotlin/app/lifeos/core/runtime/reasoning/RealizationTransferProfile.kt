package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

/**
 * B517 frozen transfer/profile contract.
 *
 * The profile fixes the admissible representation vocabulary before a realization or candidate
 * is evaluated. It is descriptive metadata only and cannot create truth, policy or execution
 * authority.
 */
data class RealizationTransferProfile private constructor(
    val profileId: String,
    val version: String,
    val requiredComponents: List<RealizationComponentKind>,
    val projectionRegistryFingerprint: String,
    val stateContractFingerprint: String,
    val observableIds: List<String>,
    val allowedAuxiliaryVariableIds: List<String>,
    val invariantIds: List<String>,
    val failureCriterionIds: List<String>,
    val frozenAt: Instant,
    val fingerprint: String,
) {
    init {
        require(version.isNotBlank()) { "Realization transfer profile version must not be blank" }
        require(requiredComponents.isNotEmpty()) {
            "Realization transfer profile requires at least one component"
        }
        require(requiredComponents == requiredComponents.distinct().sortedBy { it.name }) {
            "Required realization components must be unique and canonical"
        }
        require(projectionRegistryFingerprint.isNotBlank())
        require(stateContractFingerprint.isNotBlank())
        require(observableIds.isNotEmpty()) {
            "Realization transfer profile requires explicit observables"
        }
        require(observableIds.none { it.isBlank() })
        require(observableIds == observableIds.distinct().sorted())
        require(allowedAuxiliaryVariableIds.none { it.isBlank() })
        require(
            allowedAuxiliaryVariableIds ==
                allowedAuxiliaryVariableIds.distinct().sorted()
        )
        require(invariantIds.isNotEmpty()) {
            "Realization transfer profile requires explicit invariants"
        }
        require(invariantIds.none { it.isBlank() })
        require(invariantIds == invariantIds.distinct().sorted())
        require(failureCriterionIds.isNotEmpty()) {
            "Realization transfer profile requires explicit failure criteria"
        }
        require(failureCriterionIds.none { it.isBlank() })
        require(failureCriterionIds == failureCriterionIds.distinct().sorted())
        require(
            fingerprint == expectedFingerprint(
                version = version,
                requiredComponents = requiredComponents,
                projectionRegistryFingerprint = projectionRegistryFingerprint,
                stateContractFingerprint = stateContractFingerprint,
                observableIds = observableIds,
                allowedAuxiliaryVariableIds = allowedAuxiliaryVariableIds,
                invariantIds = invariantIds,
                failureCriterionIds = failureCriterionIds,
                frozenAt = frozenAt,
            )
        ) {
            "Realization transfer profile fingerprint does not match content"
        }
        require(profileId == "realization-profile:$fingerprint") {
            "Realization transfer profile id must bind the frozen profile fingerprint"
        }
    }

    val truthAuthority: Boolean
        get() = false

    val policyAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            version: String,
            requiredComponents: Collection<RealizationComponentKind>,
            projectionRegistryFingerprint: String,
            stateContractFingerprint: String,
            observableIds: Collection<String>,
            allowedAuxiliaryVariableIds: Collection<String> = emptyList(),
            invariantIds: Collection<String>,
            failureCriterionIds: Collection<String>,
            frozenAt: Instant,
        ): RealizationTransferProfile {
            val components = requiredComponents.distinct().sortedBy { it.name }
            val observables = observableIds.distinct().sorted()
            val auxiliaries = allowedAuxiliaryVariableIds.distinct().sorted()
            val invariants = invariantIds.distinct().sorted()
            val failureCriteria = failureCriterionIds.distinct().sorted()

            require(version.isNotBlank())
            require(components.isNotEmpty())
            require(projectionRegistryFingerprint.isNotBlank())
            require(stateContractFingerprint.isNotBlank())
            require(observables.isNotEmpty() && observables.none { it.isBlank() })
            require(auxiliaries.none { it.isBlank() })
            require(invariants.isNotEmpty() && invariants.none { it.isBlank() })
            require(failureCriteria.isNotEmpty() && failureCriteria.none { it.isBlank() })

            val fingerprint = expectedFingerprint(
                version = version,
                requiredComponents = components,
                projectionRegistryFingerprint = projectionRegistryFingerprint,
                stateContractFingerprint = stateContractFingerprint,
                observableIds = observables,
                allowedAuxiliaryVariableIds = auxiliaries,
                invariantIds = invariants,
                failureCriterionIds = failureCriteria,
                frozenAt = frozenAt,
            )
            return RealizationTransferProfile(
                profileId = "realization-profile:$fingerprint",
                version = version,
                requiredComponents = components,
                projectionRegistryFingerprint = projectionRegistryFingerprint,
                stateContractFingerprint = stateContractFingerprint,
                observableIds = observables,
                allowedAuxiliaryVariableIds = auxiliaries,
                invariantIds = invariants,
                failureCriterionIds = failureCriteria,
                frozenAt = frozenAt,
                fingerprint = fingerprint,
            )
        }

        private fun expectedFingerprint(
            version: String,
            requiredComponents: List<RealizationComponentKind>,
            projectionRegistryFingerprint: String,
            stateContractFingerprint: String,
            observableIds: List<String>,
            allowedAuxiliaryVariableIds: List<String>,
            invariantIds: List<String>,
            failureCriterionIds: List<String>,
            frozenAt: Instant,
        ): String = StableFieldIds.fingerprint(
            "realization-transfer-profile/v1",
            version,
            projectionRegistryFingerprint,
            stateContractFingerprint,
            frozenAt.toString(),
            *requiredComponents.map { "component:${it.name}" }.toTypedArray(),
            *observableIds.map { "observable:$it" }.toTypedArray(),
            *allowedAuxiliaryVariableIds.map { "auxiliary:$it" }.toTypedArray(),
            *invariantIds.map { "invariant:$it" }.toTypedArray(),
            *failureCriterionIds.map { "failure:$it" }.toTypedArray(),
        )
    }
}
