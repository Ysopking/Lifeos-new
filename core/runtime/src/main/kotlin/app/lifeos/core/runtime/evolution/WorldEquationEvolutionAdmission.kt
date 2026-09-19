package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec

data class WorldEquationEvolutionValidation internal constructor(
    val evidenceRecordFingerprint: String,
    val evidenceSetFingerprint: String,
    val protocolFingerprint: String,
    val policyFingerprint: String,
    val promotionDecisionId: String,
) {
    init {
        require(evidenceRecordFingerprint.isNotBlank())
        require(evidenceSetFingerprint.isNotBlank())
        require(protocolFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(promotionDecisionId.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-evolution-validation/v2",
        evidenceRecordFingerprint,
        evidenceSetFingerprint,
        protocolFingerprint,
        policyFingerprint,
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
        require(subject.sourceArtifactId == validation.promotionDecisionId)
        require(subject.validationBundleId == validation.evidenceRecordFingerprint)
        require(fingerprint == expectedFingerprint())
    }

    val directActivationAllowed: Boolean get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-promotion-admission/v2",
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
                "world-equation-promotion-admission/v2",
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

fun interface WorldEquationPromotionAdmissionVerifier {
    suspend fun verify(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        admission: WorldEquationPromotionAdmission,
    )
}

object RejectingWorldEquationPromotionAdmissionVerifier :
    WorldEquationPromotionAdmissionVerifier {
    override suspend fun verify(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        admission: WorldEquationPromotionAdmission,
    ) {
        error("WorldEquation promotion requires an evidence-bound admission verifier")
    }
}

class WorldEquationEvolutionAdmissionGate(
    private val evidence: WorldEquationEvidenceRepository,
    private val evaluator: WorldEquationPromotionEvaluator,
) : WorldEquationPromotionAdmissionVerifier {
    suspend fun admit(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ): WorldEquationPromotionAdmission {
        validateCandidate(candidate, baseline)
        val record = requirePromotableRecord(candidate, baseline)
        val verdict = evaluator.evaluate(candidate, baseline, record.evidence)
        require(verdict.decision == WorldEquationPromotionDecision.PROMOTABLE) {
            "WorldEquation evidence is not promotable"
        }
        require(record.latestVerdictId == verdict.id) {
            "WorldEquation durable promotion verdict does not match recomputed evidence"
        }
        val validation = WorldEquationEvolutionValidation(
            evidenceRecordFingerprint = record.fingerprint,
            evidenceSetFingerprint = record.evidence.fingerprint(),
            protocolFingerprint = record.evidence.protocol.fingerprint(),
            policyFingerprint = record.evidence.policyFingerprint,
            promotionDecisionId = verdict.id,
        )
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION,
            candidateId = "world-equation:" + candidate.version,
            sourceArtifactId = verdict.id,
            validationBundleId = record.fingerprint,
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

    override suspend fun verify(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        admission: WorldEquationPromotionAdmission,
    ) {
        validateCandidate(candidate, baseline)
        require(admission.candidateVersion == candidate.version)
        require(admission.candidateEquationFingerprint == candidate.fingerprint())
        require(admission.baselineEquationFingerprint == baseline.fingerprint())
        require(admission.candidatePhysicsFingerprint == candidate.physicsFingerprint())
        require(admission.baselinePhysicsFingerprint == baseline.physicsFingerprint())
        require(admission.equationSchemaFingerprint == candidate.schemaFingerprint())

        val record = requirePromotableRecord(candidate, baseline)
        require(record.fingerprint == admission.validation.evidenceRecordFingerprint) {
            "WorldEquation evidence changed after admission"
        }
        require(record.evidence.fingerprint() == admission.validation.evidenceSetFingerprint)
        require(record.evidence.protocol.fingerprint() == admission.validation.protocolFingerprint)
        require(record.evidence.policyFingerprint == admission.validation.policyFingerprint)
        require(record.evidence.policyFingerprint == evaluator.policy.fingerprint())

        val verdict = evaluator.evaluate(candidate, baseline, record.evidence)
        require(verdict.decision == WorldEquationPromotionDecision.PROMOTABLE)
        require(verdict.id == admission.validation.promotionDecisionId)
        require(record.latestVerdictId == verdict.id)
    }

    private suspend fun requirePromotableRecord(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ): WorldEquationEvidenceRecord {
        val record = requireNotNull(evidence.load(candidate.fingerprint())) {
            "WorldEquation promotion evidence is missing"
        }
        require(record.state == WorldEquationLifecycleState.PROMOTABLE) {
            "WorldEquation evidence state is not PROMOTABLE: " + record.state
        }
        require(record.evidence.candidateEquationFingerprint == candidate.fingerprint())
        require(record.evidence.candidatePhysicsFingerprint == candidate.physicsFingerprint())
        require(record.evidence.baselineEquationFingerprint == baseline.fingerprint())
        require(record.evidence.baselinePhysicsFingerprint == baseline.physicsFingerprint())
        require(record.evidence.equationSchemaFingerprint == candidate.schemaFingerprint())
        require(record.evidence.policyFingerprint == evaluator.policy.fingerprint())
        return record
    }

    private fun validateCandidate(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ) {
        require(candidate.version != baseline.version) {
            "World equation candidate must use a new version"
        }
        require(candidate.physicsFingerprint() != baseline.physicsFingerprint()) {
            "World equation promotion requires changed physics"
        }
        require(candidate.schemaFingerprint() == baseline.schemaFingerprint()) {
            "Current WorldEquation activation supports parameter-only changes"
        }
    }
}
