package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.level7.StrategyLearningCandidate

data class StrategyEvolutionValidation(
    val holdoutFingerprint: String,
    val shadowFingerprint: String,
    val trialFingerprint: String,
    val verifiedOutcomeFingerprint: String,
) {
    init {
        require(holdoutFingerprint.isNotBlank())
        require(shadowFingerprint.isNotBlank())
        require(trialFingerprint.isNotBlank())
        require(verifiedOutcomeFingerprint.isNotBlank())
    }

    val activationAllowed: Boolean get() = false
}

object StrategyEvolutionAdmissionGate {
    fun admit(
        candidate: StrategyLearningCandidate,
        validation: StrategyEvolutionValidation,
        baselineFingerprint: String,
    ): ControlledEvolutionSubjectRef {
        require(candidate.transitions.size >= 2)
        require(candidate.transitions.all { it.independentVerification })
        require(baselineFingerprint.isNotBlank())
        return ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.STRATEGY,
            candidateId = candidate.id,
            sourceArtifactId = validation.verifiedOutcomeFingerprint,
            validationBundleId = listOf(
                validation.holdoutFingerprint,
                validation.shadowFingerprint,
                validation.trialFingerprint,
            ).joinToString(":"),
            candidateFingerprint = candidate.fingerprint(),
            baselineFingerprint = baselineFingerprint,
        )
    }
}
