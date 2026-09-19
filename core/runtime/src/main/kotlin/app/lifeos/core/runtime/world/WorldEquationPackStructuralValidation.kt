package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

/**
 * Immutable handoff artifact for a structural WorldEquationPack that has survived preflight and
 * isolated SHADOW/HOLDOUT evaluation.
 *
 * This is intentionally not a promotion admission. It is a review/next-stage validation bundle and
 * carries no authority to mutate ProductiveWorldHead or activate a structural pack.
 */
data class WorldEquationPackStructuralValidationBundle private constructor(
    val baselinePackVersion: String,
    val baselinePackFingerprint: String,
    val baselineStructuralFingerprint: String,
    val candidatePackVersion: String,
    val candidatePackFingerprint: String,
    val candidateStructuralFingerprint: String,
    val structuralPreflightId: String,
    val evidenceRecordFingerprint: String,
    val evidenceSetFingerprint: String,
    val shadowAssessmentId: String,
    val registrySnapshotId: String,
    val registryFingerprint: String,
    val protocolFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(baselinePackVersion.isNotBlank())
        require(candidatePackVersion.isNotBlank())
        require(baselinePackVersion != candidatePackVersion)
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(baselineStructuralFingerprint.isNotBlank())
        require(candidateStructuralFingerprint.isNotBlank())
        require(baselineStructuralFingerprint != candidateStructuralFingerprint)
        require(structuralPreflightId.isNotBlank())
        require(evidenceRecordFingerprint.isNotBlank())
        require(evidenceSetFingerprint.isNotBlank())
        require(shadowAssessmentId.isNotBlank())
        require(registrySnapshotId.isNotBlank())
        require(registryFingerprint.isNotBlank())
        require(protocolFingerprint.isNotBlank())
        require(fingerprint == expectedFingerprint())
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    val automaticEvolutionAllowed: Boolean
        get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-validation/v1",
        baselinePackVersion,
        baselinePackFingerprint,
        baselineStructuralFingerprint,
        candidatePackVersion,
        candidatePackFingerprint,
        candidateStructuralFingerprint,
        structuralPreflightId,
        evidenceRecordFingerprint,
        evidenceSetFingerprint,
        shadowAssessmentId,
        registrySnapshotId,
        registryFingerprint,
        protocolFingerprint,
    )

    companion object {
        internal fun create(
            baseline: WorldEquationPack,
            candidate: WorldEquationPack,
            preflight: WorldEquationPackStructuralEvidence,
            record: WorldEquationPackEvidenceRecord,
            assessment: WorldEquationPackShadowAssessment,
        ): WorldEquationPackStructuralValidationBundle {
            val evidence = record.evidence
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-validation/v1",
                baseline.version,
                baseline.fingerprint(),
                baseline.structuralFingerprint(),
                candidate.version,
                candidate.fingerprint(),
                candidate.structuralFingerprint(),
                preflight.id,
                record.fingerprint,
                evidence.fingerprint(),
                assessment.id,
                evidence.registrySnapshotId,
                evidence.registryFingerprint,
                evidence.protocol.fingerprint(),
            )
            return WorldEquationPackStructuralValidationBundle(
                baselinePackVersion = baseline.version,
                baselinePackFingerprint = baseline.fingerprint(),
                baselineStructuralFingerprint = baseline.structuralFingerprint(),
                candidatePackVersion = candidate.version,
                candidatePackFingerprint = candidate.fingerprint(),
                candidateStructuralFingerprint = candidate.structuralFingerprint(),
                structuralPreflightId = preflight.id,
                evidenceRecordFingerprint = record.fingerprint,
                evidenceSetFingerprint = evidence.fingerprint(),
                shadowAssessmentId = assessment.id,
                registrySnapshotId = evidence.registrySnapshotId,
                registryFingerprint = evidence.registryFingerprint,
                protocolFingerprint = evidence.protocol.fingerprint(),
                fingerprint = fingerprint,
            )
        }

        internal fun restore(
            baselinePackVersion: String,
            baselinePackFingerprint: String,
            baselineStructuralFingerprint: String,
            candidatePackVersion: String,
            candidatePackFingerprint: String,
            candidateStructuralFingerprint: String,
            structuralPreflightId: String,
            evidenceRecordFingerprint: String,
            evidenceSetFingerprint: String,
            shadowAssessmentId: String,
            registrySnapshotId: String,
            registryFingerprint: String,
            protocolFingerprint: String,
            fingerprint: String,
        ): WorldEquationPackStructuralValidationBundle =
            WorldEquationPackStructuralValidationBundle(
                baselinePackVersion = baselinePackVersion,
                baselinePackFingerprint = baselinePackFingerprint,
                baselineStructuralFingerprint = baselineStructuralFingerprint,
                candidatePackVersion = candidatePackVersion,
                candidatePackFingerprint = candidatePackFingerprint,
                candidateStructuralFingerprint = candidateStructuralFingerprint,
                structuralPreflightId = structuralPreflightId,
                evidenceRecordFingerprint = evidenceRecordFingerprint,
                evidenceSetFingerprint = evidenceSetFingerprint,
                shadowAssessmentId = shadowAssessmentId,
                registrySnapshotId = registrySnapshotId,
                registryFingerprint = registryFingerprint,
                protocolFingerprint = protocolFingerprint,
                fingerprint = fingerprint,
            )
    }
}

class WorldEquationPackStructuralValidationGate(
    private val evaluator: WorldEquationPackEvidenceEvaluator =
        WorldEquationPackEvidenceEvaluator(),
) {
    fun validate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
        record: WorldEquationPackEvidenceRecord,
    ): WorldEquationPackStructuralValidationBundle {
        require(candidate.baselinePackFingerprint == baseline.fingerprint()) {
            "Structural validation candidate targets another baseline"
        }
        require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL) {
            "Structural validation rejects parameter-only candidates"
        }
        require(preflight.status == WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE) {
            "Structural validation requires a passing preflight"
        }
        require(preflight.baselinePackFingerprint == baseline.fingerprint())
        require(preflight.candidatePackFingerprint == candidate.candidate.fingerprint())
        require(record.state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED) {
            "Structural validation requires SHADOW_SUPPORTED evidence"
        }

        val evidence = record.evidence
        require(evidence.baselinePackVersion == baseline.version)
        require(evidence.baselinePackFingerprint == baseline.fingerprint())
        require(evidence.baselineStructuralFingerprint == baseline.structuralFingerprint())
        require(evidence.candidatePackVersion == candidate.candidate.version)
        require(evidence.candidatePackFingerprint == candidate.candidate.fingerprint())
        require(
            evidence.candidateStructuralFingerprint ==
                candidate.candidate.structuralFingerprint()
        )
        require(evidence.structuralPreflightId == preflight.id)
        require(evidence.registrySnapshotId == preflight.registrySnapshotId)
        require(evidence.registryFingerprint == preflight.registryFingerprint)

        val assessment = evaluator.evaluate(evidence)
        require(assessment.decision == WorldEquationPackShadowDecision.SHADOW_SUPPORTED) {
            "Structural evidence no longer supports the candidate"
        }
        require(record.latestAssessmentId == assessment.id) {
            "Durable structural assessment does not match recomputed evidence"
        }

        return WorldEquationPackStructuralValidationBundle.create(
            baseline = baseline,
            candidate = candidate.candidate,
            preflight = preflight,
            record = record,
            assessment = assessment,
        )
    }
}
