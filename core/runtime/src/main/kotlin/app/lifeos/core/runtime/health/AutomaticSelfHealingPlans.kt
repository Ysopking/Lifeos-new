package app.lifeos.core.runtime.health

import app.lifeos.core.field.StableFieldIds

data class AutomaticSelfHealingPlanBinding(
    val id: String,
    val matches: (HealthNode, HealthObservation) -> Boolean,
    val planFactory: suspend (HealthNode, HealthObservation) -> RecoveryPlan,
    val resources: SelfHealingResourceProfile,
) {
    init { require(id.isNotBlank()) }
}

data class ResolvedAutomaticSelfHealingPlan(
    val bindingId: String,
    val plan: RecoveryPlan,
    val resources: SelfHealingResourceProfile,
    val familyFingerprint: String,
)

class AutomaticSelfHealingPlanRegistry(
    bindings: List<AutomaticSelfHealingPlanBinding>,
) {
    private val bindings = bindings.sortedBy { it.id }

    init {
        require(bindings.map { it.id }.distinct().size == bindings.size) {
            "Automatic self-healing plan binding ids must be unique"
        }
    }

    suspend fun resolve(
        node: HealthNode,
        observation: HealthObservation,
    ): ResolvedAutomaticSelfHealingPlan? {
        val matches = bindings.filter { it.matches(node, observation) }
        if (matches.isEmpty()) return null
        require(matches.size == 1) {
            "Automatic self-healing plan resolution must be unambiguous for ${node.id.value}"
        }
        val binding = matches.single()
        val plan = binding.planFactory(node, observation)
        require(plan.nodeId == node.id) { "Automatic repair plan targets another health node" }
        return ResolvedAutomaticSelfHealingPlan(
            bindingId = binding.id,
            plan = plan,
            resources = binding.resources,
            familyFingerprint = StableFieldIds.fingerprint(
                "automatic-self-healing-family/v1",
                binding.id,
                node.id.value,
                node.scope.name,
                observation.classification?.category?.name.orEmpty(),
                observation.source,
                normalizeIncidentMessage(observation.message),
            ),
        )
    }
}

private fun normalizeIncidentMessage(message: String?): String = message
    .orEmpty()
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")
    .take(256)
