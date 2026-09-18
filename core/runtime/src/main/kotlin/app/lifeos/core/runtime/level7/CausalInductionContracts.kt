package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef

enum class CausalEvidenceKind {
    TEMPORAL_CORRELATION,
    CONTROLLED_INTERVENTION,
    INDEPENDENT_VERIFIED_OUTCOME,
}

data class CausalObservation(
    val id: String,
    val sourceSnapshotId: String,
    val targetSnapshotId: String,
    val sourceTarget: WorldTargetRef,
    val targetTarget: WorldTargetRef,
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
    val signedEffect: Double,
    val confidence: Double,
    val evidenceKind: CausalEvidenceKind,
    val provenanceFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(sourceSnapshotId.isNotBlank() && targetSnapshotId.isNotBlank())
        require(sourceSnapshotId != targetSnapshotId)
        require(sourceTarget != targetTarget)
        require(signedEffect.isFinite() && signedEffect in -1.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(provenanceFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-causal-observation/v1",
        id,
        sourceSnapshotId,
        targetSnapshotId,
        sourceTarget.fingerprint(),
        targetTarget.fingerprint(),
        sourceDimension.name,
        targetDimension.name,
        java.lang.Double.toHexString(signedEffect),
        java.lang.Double.toHexString(confidence),
        evidenceKind.name,
        provenanceFingerprint,
    )
}

data class CausalInductionCandidate private constructor(
    val id: String,
    val observations: List<CausalObservation>,
    val proposedMultiplier: Double,
    val proposedConfidenceMultiplier: Double,
) {
    init {
        require(observations.isNotEmpty())
        require(
            observations.any {
                it.evidenceKind == CausalEvidenceKind.CONTROLLED_INTERVENTION ||
                    it.evidenceKind == CausalEvidenceKind.INDEPENDENT_VERIFIED_OUTCOME
            }
        ) { "Temporal correlation alone cannot create causal authority" }
        require(proposedMultiplier.isFinite() && proposedMultiplier in -1.0..1.0)
        require(proposedConfidenceMultiplier.isFinite() && proposedConfidenceMultiplier in 0.0..1.0)
        require(id == expectedId())
    }

    val directWorldMutationAllowed: Boolean get() = false
    val directEquationMutationAllowed: Boolean get() = false
    val causalAuthority: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-causal-induction-candidate/v1",
        java.lang.Double.toHexString(proposedMultiplier),
        java.lang.Double.toHexString(proposedConfidenceMultiplier),
        *observations.sortedBy { it.fingerprint() }.map { it.fingerprint() }.toTypedArray(),
    )

    private fun expectedId(): String = "causal-candidate:${fingerprint()}"

    companion object {
        fun create(observations: Collection<CausalObservation>): CausalInductionCandidate {
            val canonical = observations.distinctBy { it.fingerprint() }.sortedBy { it.fingerprint() }
            require(canonical.isNotEmpty())
            val first = canonical.first()
            require(canonical.all {
                it.sourceTarget == first.sourceTarget &&
                    it.targetTarget == first.targetTarget &&
                    it.sourceDimension == first.sourceDimension &&
                    it.targetDimension == first.targetDimension
            })
            require(
                canonical.any {
                    it.evidenceKind == CausalEvidenceKind.CONTROLLED_INTERVENTION ||
                        it.evidenceKind == CausalEvidenceKind.INDEPENDENT_VERIFIED_OUTCOME
                }
            ) { "Temporal correlation alone cannot create causal authority" }
            val total = canonical.sumOf { it.confidence }.coerceAtLeast(1e-9)
            val multiplier = (canonical.sumOf { it.signedEffect * it.confidence } / total)
                .coerceIn(-1.0, 1.0)
            val confidence = canonical.maxOf { it.confidence }
            val fp = StableFieldIds.fingerprint(
                "level7-causal-induction-candidate/v1",
                java.lang.Double.toHexString(multiplier),
                java.lang.Double.toHexString(confidence),
                *canonical.map { it.fingerprint() }.toTypedArray(),
            )
            return CausalInductionCandidate(
                id = "causal-candidate:$fp",
                observations = canonical,
                proposedMultiplier = multiplier,
                proposedConfidenceMultiplier = confidence,
            )
        }
    }
}

data class CausalPromotionEvidence(
    val benchmarkFingerprint: String,
    val shadowFingerprint: String,
    val comparisonFingerprint: String,
    val trialFingerprint: String,
) {
    init {
        require(benchmarkFingerprint.isNotBlank())
        require(shadowFingerprint.isNotBlank())
        require(comparisonFingerprint.isNotBlank())
        require(trialFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-causal-promotion-evidence/v1",
        benchmarkFingerprint,
        shadowFingerprint,
        comparisonFingerprint,
        trialFingerprint,
    )
}

data class CausalEvolutionProposal(
    val candidateId: String,
    val promotionEvidenceFingerprint: String,
    val proposedEquationVersion: String,
) {
    init {
        require(candidateId.isNotBlank())
        require(promotionEvidenceFingerprint.isNotBlank())
        require(proposedEquationVersion.isNotBlank())
    }
    val activationAllowed: Boolean get() = false
}

object CausalEvolutionAdmissionGate {
    fun admit(
        candidate: CausalInductionCandidate,
        evidence: CausalPromotionEvidence,
        proposedEquationVersion: String,
    ): CausalEvolutionProposal = CausalEvolutionProposal(
        candidateId = candidate.id,
        promotionEvidenceFingerprint = evidence.fingerprint(),
        proposedEquationVersion = proposedEquationVersion,
    )
}
