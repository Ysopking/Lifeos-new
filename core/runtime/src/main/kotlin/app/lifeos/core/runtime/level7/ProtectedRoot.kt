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

data class MutationTarget(
    val path: String,
    val type: String,
) {
    init {
        require(path.isNotBlank())
        require(type.isNotBlank())
    }
}

class ProtectedRootRegistry(
    private val rules: Map<ProtectedRootComponent, Set<String>> = defaultRules(),
) {
    fun classify(target: MutationTarget): ProtectedRootComponent? {
        val normalized = (target.path + "|" + target.type).lowercase()
        return rules.entries
            .sortedBy { it.key.name }
            .firstOrNull { (_, tokens) -> tokens.any(normalized::contains) }
            ?.key
    }

    companion object {
        fun defaultRules(): Map<ProtectedRootComponent, Set<String>> = mapOf(
            ProtectedRootComponent.OWNER_POLICY to setOf("ownerpolicy", "owner-policy"),
            ProtectedRootComponent.EXECUTION_GATE to setOf("executiongate", "execution-guard"),
            ProtectedRootComponent.CRYPTOGRAPHIC_STORAGE to setOf("encrypted", "keystore", "crypto"),
            ProtectedRootComponent.PHOTON_REVISION_IDENTITY to setOf("photonindex", "revisionedphoton"),
            ProtectedRootComponent.PROVENANCE to setOf("provenance"),
            ProtectedRootComponent.DECISION_TRACE to setOf("decisiontrace"),
            ProtectedRootComponent.RESOURCE_LIMITS to setOf("resourcebudget", "resource-limit"),
            ProtectedRootComponent.BOOTENGINE_LIFECYCLE to setOf("bootengine", "continuousboot"),
            ProtectedRootComponent.WORLD_FORMULA_SEMANTICS to setOf("worldformula", "world-equation"),
            ProtectedRootComponent.WORLD_EQUATION_PROMOTION to setOf("equationpromotion", "world-coefficient"),
            ProtectedRootComponent.PRODUCTIVE_WORLD_HEAD to setOf("productiveworldhead"),
            ProtectedRootComponent.CONVERGENCE_SEPARATION to setOf("productiveconvergence", "convergenceauthority"),
            ProtectedRootComponent.EXTENSION_ACTIVATION to setOf("extensionhotswap", "extensionregistryhead"),
            ProtectedRootComponent.ROLLBACK to setOf("rollback"),
            ProtectedRootComponent.ROOT_REGISTRY to setOf("protectedroot"),
        )
    }
}

data class RootMutationRequest(
    val subjectId: String,
    val target: MutationTarget,
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

class MutationBoundaryValidator(
    private val registry: ProtectedRootRegistry = ProtectedRootRegistry(),
) {
    fun evaluate(request: RootMutationRequest): RootMutationDecision =
        registry.classify(request.target)?.let { component ->
            RootMutationDecision.BlockedProtectedRoot(component)
        } ?: RootMutationDecision.CandidateAllowed(request)
}

object ProtectedRootFirewall {
    private val validator = MutationBoundaryValidator()

    fun evaluate(request: RootMutationRequest): RootMutationDecision =
        validator.evaluate(request)
}
