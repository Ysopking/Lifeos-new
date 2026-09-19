package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec

data class WorldEquationEvolutionValidation(
    val holdoutEvidenceId: String,
    val shadowEvidenceId: String,
    val trialEvidenceId: String,
    val promotionDecisionId: String,
) {
    init {
        require(holdoutEvidenceId.isNotBlank())
        require(shadowEvidenceId.isNotBlank())
        require(trialEvidenceId.isNotBlank())
        require(promotionDecisionId.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-evolution-validation/v1",
        holdoutEvidenceId,
        shadowEvidenceId,
        trialEvidenceId,
        promotionDecisionId,
    )
}

data class WorldEquationPromotionAdmission internal constructor(
    val subject: ControlledEvolutionSubjectRef,
    val candidateVersion: String,
    val candidateEquationFingerprint: String,
    val baselineEquationFingerprint: String,
    val candidatePhysicsFingerprint: String,
    val baselinePhysicsFingerprint: String,
    val equationSchemaFingerprint: String,
    val validation: WorldEquationEvolutionValidation,
    val fingerprint: String,
) {
    init {
        require(subject.kind == ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION)
        require(candidateVersion.isNotBlank())
        require(candidateEquationFingerprint.isNotBlank())
        require(baselineEquationFingerprint.isNotBlank())
        require(candidatePhysicsFingerprint.isNotBlank())
        require(baselinePhysicsFingerprint.isNotBlank())
        require(equationSchemaFingerprint.isNotBlank())
        require(candidateEquationFingerprint != baselineEquationFingerprint)
        require(candidatePhysicsFingerprint != baselinePhysicsFingerprint)
        require(subject.candidateFingerprint == candidateEquationFingerprint)
        require(subject.baselineFingerprint == baselineEquationFingerprint)
        require(fingerprint == expectedFingerprint())
    }

    val directActivationAllowed: Boolean get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-promotion-admission/v1",
        subject.fingerprint(),
        candidateVersion,
        candidateEquationFingerprint,
        baselineEquationFingerprint,
        candidatePhysicsFingerprint,
        baselinePhysicsFingerprint,
        equationSchemaFingerprint,
        validation.fingerprint(),
    )

    companion object {
        internal fun create(
            subject: ControlledEvolutionSubjectRef,
            candidate: WorldEquationSpec,
            baseline: WorldEquationSpec,
            validation: WorldEquationEvolutionValidation,
        ): WorldEquationPromotionAdmission {
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-promotion-admission/v1",
                subject.fingerprint(),
                candidate.version,
                candidate.fingerprint(),
                baseline.fingerprint(),
                candidate.physicsFingerprint(),
                baseline.physicsFingerprint(),
                candidate.schemaFingerprint(),
                validation.fingerprint(),
            )
            return WorldEquationPromotionAdmission(
                subject = subject,
                candidateVersion = candidate.version,
                candidateEquationFingerprint = candidate.fingerprint(),
                baselineEquationFingerprint = baseline.fingerprint(),
                candidatePhysicsFingerprint = candidate.physicsFingerprint(),
                baselinePhysicsFingerprint = baseline.physicsFingerprint(),
                equationSchemaFingerprint = candidate.schemaFingerprint(),
                validation = validation,
                fingerprint = fingerprint,
            )
        }
    }
}

object WorldEquationEvolutionAdmissionGate {
    fun admit(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        validation: WorldEquationEvolutionValidation,
    ): WorldEquationPromotionAdmission {
        require(candidate.version != baseline.version) {
            "World equation candidate must use a new version"
        }
        require(candidate.physicsFingerprint() != baseline.physicsFingerprint()) {
            "World equation promotion requires changed physics"
        }
        require(candidate.schemaFingerprint() == baseline.schemaFingerprint()) {
            "Current WorldEquation activation supports parameter-only changes"
        }
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION,
            candidateId = "world-equation:${candidate.version}",
            sourceArtifactId = validation.promotionDecisionId,
            validationBundleId = validation.fingerprint(),
            candidateFingerprint = candidate.fingerprint(),
            baselineFingerprint = baseline.fingerprint(),
        )
        return WorldEquationPromotionAdmission.create(
            subject = subject,
            candidate = candidate,
            baseline = baseline,
            validation = validation,
        )
    }
}
