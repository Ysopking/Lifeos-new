package app.lifeos.core.runtime.level7

data class BoundedCausalActionProposal(
    val request: CausalDiscriminationRequest,
    val actionKind: EvidenceActionKind,
    val resourceCost: Double,
) {
    init {
        require(resourceCost.isFinite() && resourceCost > 0.0)
    }

    val executionAuthority: Boolean get() = false
}

class CausalDiscriminationEngine {
    fun propose(
        requests: List<CausalDiscriminationRequest>,
        maxActions: Int = 4,
    ): List<BoundedCausalActionProposal> {
        require(maxActions in 1..16)
        return requests
            .sortedByDescending { it.expectedInformationGain }
            .take(maxActions)
            .map { request ->
                BoundedCausalActionProposal(
                    request = request,
                    actionKind = EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
                    resourceCost = 1.0 / request.expectedInformationGain.coerceAtLeast(0.01),
                )
            }
    }
}
