package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

/**
 * Narrow authorization for non-productive structural canary sandbox execution.
 *
 * This admission can only be derived from an exact durable canary plan whose repository is healthy.
 * It does not authorize productive activation, ProductiveWorldHead mutation, cognitive side effects,
 * or promotion.
 */
data class WorldEquationPackStructuralCanaryAdmission private constructor(
    val planFingerprint: String,
    val validationBundleFingerprint: String,
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val id: String,
    val fingerprint: String,
) {
    init {
        require(planFingerprint.isNotBlank())
        require(validationBundleFingerprint.isNotBlank())
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(id == expectedId())
        require(fingerprint == expectedFingerprint())
    }

    val isolatedExecutionAllowed: Boolean
        get() = true

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    val cognitiveSideEffectsAllowed: Boolean
        get() = false

    private fun expectedId(): String =
        "world-equation-pack-structural-canary-admission:" + StableFieldIds.fingerprint(
            "world-equation-pack-structural-canary-admission-id/v1",
            planFingerprint,
            candidatePackFingerprint,
        )

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-admission/v1",
        id,
        planFingerprint,
        validationBundleFingerprint,
        baselinePackFingerprint,
        candidatePackFingerprint,
    )

    companion object {
        internal fun create(
            plan: WorldEquationPackStructuralCanaryPlan,
        ): WorldEquationPackStructuralCanaryAdmission {
            val id = "world-equation-pack-structural-canary-admission:" +
                StableFieldIds.fingerprint(
                    "world-equation-pack-structural-canary-admission-id/v1",
                    plan.fingerprint,
                    plan.candidatePackFingerprint,
                )
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-admission/v1",
                id,
                plan.fingerprint,
                plan.validationBundleFingerprint,
                plan.baselinePackFingerprint,
                plan.candidatePackFingerprint,
            )
            return WorldEquationPackStructuralCanaryAdmission(
                planFingerprint = plan.fingerprint,
                validationBundleFingerprint = plan.validationBundleFingerprint,
                baselinePackFingerprint = plan.baselinePackFingerprint,
                candidatePackFingerprint = plan.candidatePackFingerprint,
                id = id,
                fingerprint = fingerprint,
            )
        }
    }
}

class WorldEquationPackStructuralCanaryAdmissionGate(
    private val plans: WorldEquationPackStructuralCanaryPlanRepository,
) {
    suspend fun admit(
        plan: WorldEquationPackStructuralCanaryPlan,
    ): WorldEquationPackStructuralCanaryAdmission {
        val report = plans.loadReport()
        require(!report.corrupted) {
            "Structural canary plan repository is corrupted; sandbox admission is fail-closed"
        }
        val durable = requireNotNull(plans.load(plan.fingerprint)) {
            "Structural canary sandbox admission requires a durable plan"
        }
        require(durable == plan) {
            "Structural canary sandbox admission plan does not match durable content"
        }
        return WorldEquationPackStructuralCanaryAdmission.create(plan)
    }
}
