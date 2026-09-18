package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class MetaAdaptationTarget {
    ATTENTION_POLICY,
    SEARCH_BUDGET,
    STRATEGY_SELECTOR,
    PROJECTION_WEIGHT,
    CALIBRATION_POLICY,
}

data class MetaAdaptationCandidate private constructor(
    val id: String,
    val target: MetaAdaptationTarget,
    val baselineFingerprint: String,
    val proposedFingerprint: String,
    val evidenceFingerprint: String,
    val evaluatorFingerprint: String,
) {
    init {
        require(baselineFingerprint.isNotBlank())
        require(proposedFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(evaluatorFingerprint.isNotBlank())
        require(baselineFingerprint != proposedFingerprint)
        require(id == expectedId())
    }

    val protectedRootMutationAllowed: Boolean get() = false
    val activationAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-meta-adaptation-candidate/v1",
        target.name,
        baselineFingerprint,
        proposedFingerprint,
        evidenceFingerprint,
        evaluatorFingerprint,
    )

    private fun expectedId(): String = "meta-adaptation:${fingerprint()}"

    companion object {
        fun create(
            target: MetaAdaptationTarget,
            baselineFingerprint: String,
            proposedFingerprint: String,
            evidenceFingerprint: String,
            evaluatorFingerprint: String,
        ): MetaAdaptationCandidate {
            val fp = StableFieldIds.fingerprint(
                "level7-meta-adaptation-candidate/v1",
                target.name,
                baselineFingerprint,
                proposedFingerprint,
                evidenceFingerprint,
                evaluatorFingerprint,
            )
            return MetaAdaptationCandidate(
                id = "meta-adaptation:$fp",
                target = target,
                baselineFingerprint = baselineFingerprint,
                proposedFingerprint = proposedFingerprint,
                evidenceFingerprint = evidenceFingerprint,
                evaluatorFingerprint = evaluatorFingerprint,
            )
        }
    }
}
