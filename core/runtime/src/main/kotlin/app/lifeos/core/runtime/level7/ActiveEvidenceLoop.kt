package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class EvidenceActionKind {
    LOCAL_RETRIEVAL,
    MEMORY_LOOKUP,
    DEEP_SEARCH,
    SOURCE_REFRESH,
    SIMULATION,
    SAFE_SANDBOX_EXPERIMENT,
    ASK_USER,
    ABSTAIN,
}

data class EvidenceActionRequest private constructor(
    val id: String,
    val sourceCycleId: String,
    val gapFingerprint: String,
    val kind: EvidenceActionKind,
    val rationale: String,
    val budgetFingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(gapFingerprint.isNotBlank())
        require(rationale.isNotBlank())
        require(budgetFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val executionAuthority: Boolean get() = false
    val currentCycleWorldMutationAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-evidence-action-request/v1",
        sourceCycleId,
        gapFingerprint,
        kind.name,
        rationale,
        budgetFingerprint,
    )

    private fun expectedId(): String = "evidence-action:${fingerprint()}"

    companion object {
        fun create(
            sourceCycleId: String,
            gapFingerprint: String,
            kind: EvidenceActionKind,
            rationale: String,
            budgetFingerprint: String,
        ): EvidenceActionRequest {
            val fp = StableFieldIds.fingerprint(
                "level7-evidence-action-request/v1",
                sourceCycleId,
                gapFingerprint,
                kind.name,
                rationale,
                budgetFingerprint,
            )
            return EvidenceActionRequest(
                id = "evidence-action:$fp",
                sourceCycleId = sourceCycleId,
                gapFingerprint = gapFingerprint,
                kind = kind,
                rationale = rationale,
                budgetFingerprint = budgetFingerprint,
            )
        }
    }
}

data class EvidenceObservation(
    val requestId: String,
    val observationPhotonRef: String,
    val observationFingerprint: String,
    val verified: Boolean,
) {
    init {
        require(requestId.isNotBlank())
        require(observationPhotonRef.isNotBlank())
        require(observationFingerprint.isNotBlank())
    }

    val entersNextCycleOnly: Boolean get() = true
}
