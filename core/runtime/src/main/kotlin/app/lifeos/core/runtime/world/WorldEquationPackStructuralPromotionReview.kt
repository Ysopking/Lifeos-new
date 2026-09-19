package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

/**
 * Immutable handoff for explicit structural promotion review.
 *
 * Reaching this boundary means the structural candidate survived preflight, SHADOW/HOLDOUT,
 * durable validation, a bounded canary design, and deterministic non-productive canary replay.
 * It still grants no promotion or productive activation authority.
 */
data class WorldEquationPackStructuralPromotionReviewBundle private constructor(
    val validationBundleFingerprint: String,
    val canaryPlanFingerprint: String,
    val canaryEvidenceRecordFingerprint: String,
    val canaryAssessmentId: String,
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(validationBundleFingerprint.isNotBlank())
        require(canaryPlanFingerprint.isNotBlank())
        require(canaryEvidenceRecordFingerprint.isNotBlank())
        require(canaryAssessmentId.isNotBlank())
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(fingerprint == expectedFingerprint())
    }

    val ownerApprovalRequired: Boolean
        get() = true

    val automaticPromotionAllowed: Boolean
        get() = false

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-promotion-review/v1",
        validationBundleFingerprint,
        canaryPlanFingerprint,
        canaryEvidenceRecordFingerprint,
        canaryAssessmentId,
        baselinePackFingerprint,
        candidatePackFingerprint,
    )

    companion object {
        internal fun create(
            validation: WorldEquationPackStructuralValidationBundle,
            plan: WorldEquationPackStructuralCanaryPlan,
            record: WorldEquationPackStructuralCanaryEvidenceRecord,
            assessment: WorldEquationPackStructuralCanaryAssessment,
        ): WorldEquationPackStructuralPromotionReviewBundle {
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-promotion-review/v1",
                validation.fingerprint,
                plan.fingerprint,
                record.fingerprint,
                assessment.id,
                validation.baselinePackFingerprint,
                validation.candidatePackFingerprint,
            )
            return WorldEquationPackStructuralPromotionReviewBundle(
                validationBundleFingerprint = validation.fingerprint,
                canaryPlanFingerprint = plan.fingerprint,
                canaryEvidenceRecordFingerprint = record.fingerprint,
                canaryAssessmentId = assessment.id,
                baselinePackFingerprint = validation.baselinePackFingerprint,
                candidatePackFingerprint = validation.candidatePackFingerprint,
                fingerprint = fingerprint,
            )
        }

        internal fun restore(
            validationBundleFingerprint: String,
            canaryPlanFingerprint: String,
            canaryEvidenceRecordFingerprint: String,
            canaryAssessmentId: String,
            baselinePackFingerprint: String,
            candidatePackFingerprint: String,
            fingerprint: String,
        ): WorldEquationPackStructuralPromotionReviewBundle =
            WorldEquationPackStructuralPromotionReviewBundle(
                validationBundleFingerprint = validationBundleFingerprint,
                canaryPlanFingerprint = canaryPlanFingerprint,
                canaryEvidenceRecordFingerprint = canaryEvidenceRecordFingerprint,
                canaryAssessmentId = canaryAssessmentId,
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                fingerprint = fingerprint,
            )
    }
}

class WorldEquationPackStructuralPromotionReviewGate(
    private val evaluator: WorldEquationPackStructuralCanaryEvidenceEvaluator =
        WorldEquationPackStructuralCanaryEvidenceEvaluator(),
) {
    fun build(
        validation: WorldEquationPackStructuralValidationBundle,
        plan: WorldEquationPackStructuralCanaryPlan,
        record: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): WorldEquationPackStructuralPromotionReviewBundle {
        require(plan.validationBundleFingerprint == validation.fingerprint) {
            "Structural promotion review plan targets another validation bundle"
        }
        require(plan.baselinePackFingerprint == validation.baselinePackFingerprint)
        require(plan.candidatePackFingerprint == validation.candidatePackFingerprint)
        require(record.evidence.planFingerprint == plan.fingerprint)
        require(
            record.evidence.candidatePackFingerprint ==
                validation.candidatePackFingerprint
        )
        require(
            record.state ==
                WorldEquationPackStructuralCanaryLifecycleState.CANARY_SUPPORTED
        ) {
            "Structural promotion review requires CANARY_SUPPORTED evidence"
        }

        val assessment = evaluator.evaluate(record.evidence)
        require(
            assessment.decision ==
                WorldEquationPackStructuralCanaryDecision.CANARY_SUPPORTED
        ) {
            "Structural canary evidence no longer supports review"
        }
        require(record.latestAssessmentId == assessment.id) {
            "Structural canary durable assessment does not match recomputed evidence"
        }

        return WorldEquationPackStructuralPromotionReviewBundle.create(
            validation = validation,
            plan = plan,
            record = record,
            assessment = assessment,
        )
    }
}
