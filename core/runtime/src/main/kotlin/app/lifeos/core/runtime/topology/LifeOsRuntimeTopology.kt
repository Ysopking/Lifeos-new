package app.lifeos.core.runtime.topology

import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType

enum class LifeOsSubsystemState {
    REGISTERED,
    ACTIVE,
    DEGRADED,
    UNAVAILABLE,
}

data class LifeOsSubsystemDescriptor(
    val id: String,
    val requiredCapabilities: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank())
        require(requiredCapabilities.none { it.isBlank() })
        require(dependencies.none { it.isBlank() })
        require(id !in dependencies)
    }
}

data class LifeOsSubsystemStatus(
    val descriptor: LifeOsSubsystemDescriptor,
    val state: LifeOsSubsystemState,
    val activeProviderIds: Set<String> = emptySet(),
    val unavailableCapabilities: Set<String> = emptySet(),
)

data class LifeOsRuntimeTopologySnapshot(
    val subsystems: List<LifeOsSubsystemStatus>,
    val capabilityProviderCount: Int,
    val generatedProviderCount: Int,
) {
    val registeredSubsystemCount: Int get() = subsystems.size
    val unavailableSubsystems: List<LifeOsSubsystemStatus>
        get() = subsystems.filter { it.state == LifeOsSubsystemState.UNAVAILABLE }
}

/**
 * Process-wide topology projection for the productive LIFEOS runtime.
 * Static subsystems describe the canonical architecture; capability/provider state remains dynamic,
 * so generated tools and hot swaps become visible without changing this inventory.
 */
object LifeOsProcessTopology {
    val canonicalSubsystems: List<LifeOsSubsystemDescriptor> = listOf(
        LifeOsSubsystemDescriptor("photon-store"),
        LifeOsSubsystemDescriptor("binary-asset-store"),
        LifeOsSubsystemDescriptor("thought-matrix"),
        LifeOsSubsystemDescriptor("thought-graph", dependencies = setOf("photon-store")),
        LifeOsSubsystemDescriptor("field-runtime", dependencies = setOf("thought-matrix")),
        LifeOsSubsystemDescriptor("field-thought-graph-projection", dependencies = setOf("field-runtime", "thought-graph")),
        LifeOsSubsystemDescriptor("world-formula", dependencies = setOf("field-runtime")),
        LifeOsSubsystemDescriptor("language-understanding", requiredCapabilities = setOf("language.understand")),
        LifeOsSubsystemDescriptor("language-context", dependencies = setOf("photon-store")),
        LifeOsSubsystemDescriptor("goal-planning", dependencies = setOf("language-understanding")),
        LifeOsSubsystemDescriptor("goal-resume", requiredCapabilities = setOf("goal.resume"), dependencies = setOf("goal-planning")),
        LifeOsSubsystemDescriptor("capability-registry"),
        LifeOsSubsystemDescriptor("capability-router", dependencies = setOf("capability-registry")),
        LifeOsSubsystemDescriptor("local-knowledge", requiredCapabilities = setOf("knowledge.resolve")),
        LifeOsSubsystemDescriptor("deep-search", requiredCapabilities = setOf("deepsearch.query")),
        LifeOsSubsystemDescriptor("scene-compiler", requiredCapabilities = setOf("scene.construct.procedural")),
        LifeOsSubsystemDescriptor("scene-rasterizer", requiredCapabilities = setOf("scene.rasterize.mmsi")),
        LifeOsSubsystemDescriptor("image-renderer", requiredCapabilities = setOf("image.render.mmsi")),
        LifeOsSubsystemDescriptor("image-transform", requiredCapabilities = setOf("image.transform.mmsi")),
        LifeOsSubsystemDescriptor("reminder-scheduler", requiredCapabilities = setOf("planner.schedule")),
        LifeOsSubsystemDescriptor("communication", requiredCapabilities = setOf("communication.dispatch")),
        LifeOsSubsystemDescriptor("continuous-cognition", dependencies = setOf("photon-store", "durable-task-engine")),
        LifeOsSubsystemDescriptor("cognition-reconciler", dependencies = setOf("continuous-cognition")),
        LifeOsSubsystemDescriptor("cognition-outcome-pipeline", dependencies = setOf("continuous-cognition")),
        LifeOsSubsystemDescriptor("durable-task-engine"),
        LifeOsSubsystemDescriptor("cognitive-worker", dependencies = setOf("durable-task-engine", "field-runtime")),
        LifeOsSubsystemDescriptor("task-scheduler", dependencies = setOf("durable-task-engine", "cognitive-worker")),
        LifeOsSubsystemDescriptor("lease-recovery", dependencies = setOf("durable-task-engine")),
        LifeOsSubsystemDescriptor("runtime-supervisor", dependencies = setOf("task-scheduler", "lease-recovery")),
        LifeOsSubsystemDescriptor("health-graph"),
        LifeOsSubsystemDescriptor("protection-coordinator", dependencies = setOf("health-graph")),
        LifeOsSubsystemDescriptor("self-healing", dependencies = setOf("health-graph", "runtime-supervisor")),
        LifeOsSubsystemDescriptor("tool-workshop", dependencies = setOf("capability-registry")),
        LifeOsSubsystemDescriptor("generated-tool-registry", dependencies = setOf("tool-workshop", "capability-registry")),
        LifeOsSubsystemDescriptor("evolution-hot-swap", dependencies = setOf("generated-tool-registry", "capability-registry")),
        LifeOsSubsystemDescriptor("learning-adaptation", dependencies = setOf("field-runtime", "capability-router")),
    ).also { descriptors ->
        require(descriptors.size == 36) { "Canonical LIFEOS topology must contain exactly 36 subsystems" }
        require(descriptors.map { it.id }.distinct().size == descriptors.size) {
            "Canonical LIFEOS subsystem ids must be unique"
        }
    }

    suspend fun snapshot(): LifeOsRuntimeTopologySnapshot? {
        val registry = GeneratedToolRuntimeProcessRegistry.capabilities() ?: return null
        val providers = registry.all(includeUnavailable = true)
        val providersByCapability = providers.groupBy { it.capabilityId.value }
        val statuses = canonicalSubsystems.map { descriptor ->
            if (descriptor.requiredCapabilities.isEmpty()) {
                LifeOsSubsystemStatus(descriptor, LifeOsSubsystemState.REGISTERED)
            } else {
                val matching = descriptor.requiredCapabilities.flatMap { providersByCapability[it].orEmpty() }
                val unavailable = descriptor.requiredCapabilities.filterTo(linkedSetOf()) { capability ->
                    providersByCapability[capability].orEmpty().none { provider ->
                        provider.state == ProviderState.ACTIVE || provider.state == ProviderState.DEGRADED
                    }
                }
                val activeProviders = matching
                    .filter { it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED }
                    .mapTo(linkedSetOf()) { it.providerId }
                val state = when {
                    unavailable.isNotEmpty() -> LifeOsSubsystemState.UNAVAILABLE
                    matching.any { it.state == ProviderState.DEGRADED } -> LifeOsSubsystemState.DEGRADED
                    else -> LifeOsSubsystemState.ACTIVE
                }
                LifeOsSubsystemStatus(
                    descriptor = descriptor,
                    state = state,
                    activeProviderIds = activeProviders,
                    unavailableCapabilities = unavailable,
                )
            }
        }
        return LifeOsRuntimeTopologySnapshot(
            subsystems = statuses,
            capabilityProviderCount = providers.size,
            generatedProviderCount = providers.count { it.providerType == ProviderType.GENERATED_TOOL },
        )
    }
}
