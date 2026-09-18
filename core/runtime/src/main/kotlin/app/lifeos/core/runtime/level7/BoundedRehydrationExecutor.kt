package app.lifeos.core.runtime.level7

fun interface ExactRehydrationResolver {
    suspend fun resolve(exactRef: String): String
}

data class RehydratedExactState(
    val kind: RehydrationStepKind,
    val exactRef: String,
    val resolvedFingerprint: String,
) {
    init {
        require(exactRef.isNotBlank())
        require(resolvedFingerprint.isNotBlank())
    }
}

data class BoundedRehydrationResult(
    val planId: String,
    val states: List<RehydratedExactState>,
    val semanticCheckpoint: ProcessDeathSemanticCheckpoint?,
) {
    init {
        require(planId.isNotBlank())
        require(states.map { it.kind }.distinct().size == states.size)
    }
}

class BoundedRehydrationExecutor(
    private val resolvers: Map<RehydrationStepKind, ExactRehydrationResolver>,
) {
    suspend fun execute(
        plan: BoundedRehydrationPlan,
        checkpointFactory: ((Map<RehydrationStepKind, RehydratedExactState>) -> ProcessDeathSemanticCheckpoint)? = null,
    ): BoundedRehydrationResult {
        require(plan.steps.size <= BoundedRehydrationPlan.MAX_STEPS)
        val resolved = linkedMapOf<RehydrationStepKind, RehydratedExactState>()
        plan.steps.forEach { step ->
            val resolver = requireNotNull(resolvers[step.kind]) {
                "Missing exact rehydration resolver for ${step.kind}"
            }
            val fingerprint = resolver.resolve(step.exactRef)
            require(fingerprint.isNotBlank()) {
                "Exact rehydration resolver returned blank fingerprint for ${step.kind}"
            }
            resolved[step.kind] = RehydratedExactState(
                kind = step.kind,
                exactRef = step.exactRef,
                resolvedFingerprint = fingerprint,
            )
            validateDependencies(step.kind, resolved)
        }
        return BoundedRehydrationResult(
            planId = plan.id,
            states = resolved.values.toList(),
            semanticCheckpoint = checkpointFactory?.invoke(resolved),
        )
    }

    private fun validateDependencies(
        kind: RehydrationStepKind,
        resolved: Map<RehydrationStepKind, RehydratedExactState>,
    ) {
        when (kind) {
            RehydrationStepKind.PRODUCTIVE_WORLD_SNAPSHOT ->
                require(RehydrationStepKind.PRODUCTIVE_WORLD_HEAD in resolved)
            RehydrationStepKind.ACTIVE_BOOTENGINE_CYCLE -> {
                require(RehydrationStepKind.PRODUCTIVE_WORLD_HEAD in resolved)
                require(RehydrationStepKind.WORLD_EQUATION_HEAD in resolved)
            }
            RehydrationStepKind.ACTIVE_EXTENSION_SNAPSHOT ->
                require(RehydrationStepKind.EXTENSION_REGISTRY_HEAD in resolved)
            RehydrationStepKind.DYNAMIC_MODULE_HEAD ->
                require(RehydrationStepKind.ACTIVE_EXTENSION_SNAPSHOT in resolved)
            RehydrationStepKind.STRATEGY_HEAD ->
                require(RehydrationStepKind.WORLD_MODEL_HEAD in resolved)
            else -> Unit
        }
    }
}
