package app.lifeos.core.runtime.level7

enum class ProtectedRootComponent {
    OWNER_POLICY,
    EXECUTION_GATE,
    CRYPTOGRAPHIC_STORAGE,
    PHOTON_REVISION_IDENTITY,
    PROVENANCE,
    DECISION_TRACE,
    RESOURCE_LIMITS,
    BOOTENGINE_LIFECYCLE,
    WORLD_FORMULA_SEMANTICS,
    WORLD_EQUATION_PROMOTION,
    PRODUCTIVE_WORLD_HEAD,
    CONVERGENCE_SEPARATION,
    EXTENSION_ACTIVATION,
    ROLLBACK,
    ROOT_REGISTRY,
}

data class RootMutationRequest(
    val subjectId: String,
    val component: ProtectedRootComponent?,
    val candidateFingerprint: String,
) {
    init {
        require(subjectId.isNotBlank())
        require(candidateFingerprint.isNotBlank())
    }
}

sealed interface RootMutationDecision {
    data class CandidateAllowed(val request: RootMutationRequest) : RootMutationDecision
    data class BlockedProtectedRoot(val component: ProtectedRootComponent) : RootMutationDecision
}

object ProtectedRootFirewall {
    fun evaluate(request: RootMutationRequest): RootMutationDecision =
        request.component?.let { component ->
            RootMutationDecision.BlockedProtectedRoot(component)
        } ?: RootMutationDecision.CandidateAllowed(request)
}
