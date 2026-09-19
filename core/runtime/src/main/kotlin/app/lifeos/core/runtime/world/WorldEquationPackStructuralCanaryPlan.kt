package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

/**
 * Non-executing design artifact for a future structural canary stage.
 *
 * A plan is only a bounded proposal. It cannot execute a candidate, mutate ProductiveWorldHead,
 * admit promotion, or activate a WorldEquationPack.
 */
data class WorldEquationPackStructuralCanaryPlan private constructor(
    val validationBundleFingerprint: String,
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val maximumCases: Int,
    val maximumConsecutiveFailures: Int,
    val requireHoldoutRecheck: Boolean,
    val requireColdRestartRecovery: Boolean,
    val version: String,
    val fingerprint: String,
) {
    init {
        require(validationBundleFingerprint.isNotBlank())
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(maximumCases in 1..MAXIMUM_CASES_LIMIT)
        require(maximumConsecutiveFailures in 1..maximumCases)
        require(version.isNotBlank())
        require(fingerprint == expectedFingerprint())
    }

    val executionAllowed: Boolean
        get() = false

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-plan/v1",
        validationBundleFingerprint,
        baselinePackFingerprint,
        candidatePackFingerprint,
        maximumCases.toString(),
        maximumConsecutiveFailures.toString(),
        requireHoldoutRecheck.toString(),
        requireColdRestartRecovery.toString(),
        version,
    )

    companion object {
        const val MAXIMUM_CASES_LIMIT = 10_000

        fun create(
            validation: WorldEquationPackStructuralValidationBundle,
            maximumCases: Int,
            maximumConsecutiveFailures: Int,
            requireHoldoutRecheck: Boolean = true,
            requireColdRestartRecovery: Boolean = true,
            version: String = "structural-canary-plan-v1",
        ): WorldEquationPackStructuralCanaryPlan {
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-plan/v1",
                validation.fingerprint,
                validation.baselinePackFingerprint,
                validation.candidatePackFingerprint,
                maximumCases.toString(),
                maximumConsecutiveFailures.toString(),
                requireHoldoutRecheck.toString(),
                requireColdRestartRecovery.toString(),
                version,
            )
            return WorldEquationPackStructuralCanaryPlan(
                validationBundleFingerprint = validation.fingerprint,
                baselinePackFingerprint = validation.baselinePackFingerprint,
                candidatePackFingerprint = validation.candidatePackFingerprint,
                maximumCases = maximumCases,
                maximumConsecutiveFailures = maximumConsecutiveFailures,
                requireHoldoutRecheck = requireHoldoutRecheck,
                requireColdRestartRecovery = requireColdRestartRecovery,
                version = version,
                fingerprint = fingerprint,
            )
        }

        internal fun restore(
            validationBundleFingerprint: String,
            baselinePackFingerprint: String,
            candidatePackFingerprint: String,
            maximumCases: Int,
            maximumConsecutiveFailures: Int,
            requireHoldoutRecheck: Boolean,
            requireColdRestartRecovery: Boolean,
            version: String,
            fingerprint: String,
        ): WorldEquationPackStructuralCanaryPlan =
            WorldEquationPackStructuralCanaryPlan(
                validationBundleFingerprint = validationBundleFingerprint,
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                maximumCases = maximumCases,
                maximumConsecutiveFailures = maximumConsecutiveFailures,
                requireHoldoutRecheck = requireHoldoutRecheck,
                requireColdRestartRecovery = requireColdRestartRecovery,
                version = version,
                fingerprint = fingerprint,
            )
    }
}

class WorldEquationPackStructuralCanaryPlanner {
    fun create(
        validation: WorldEquationPackStructuralValidationBundle,
        recovery: WorldEquationPackStructuralValidationRecoveryReport,
        maximumCases: Int,
        maximumConsecutiveFailures: Int,
    ): WorldEquationPackStructuralCanaryPlan {
        require(!recovery.failClosed) {
            "Structural canary design is blocked by corrupted validation recovery state"
        }
        val durable = recovery.bundles.singleOrNull {
            it.candidatePackFingerprint == validation.candidatePackFingerprint
        }
        require(durable != null && durable.fingerprint == validation.fingerprint) {
            "Structural canary design requires the exact recovered validation bundle"
        }
        return WorldEquationPackStructuralCanaryPlan.create(
            validation = validation,
            maximumCases = maximumCases,
            maximumConsecutiveFailures = maximumConsecutiveFailures,
        )
    }
}
