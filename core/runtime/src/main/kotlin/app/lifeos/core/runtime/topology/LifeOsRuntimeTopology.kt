package app.lifeos.core.runtime.topology

import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.MultimodalPerceptionCapabilities
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
    val binding: LifeOsRuntimeBinding? = null,
    val activeProviderIds: Set<String> = emptySet(),
    val unavailableCapabilities: Set<String> = emptySet(),
    val unavailableDependencies: Set<String> = emptySet(),
)

data class LifeOsRuntimeTopologySnapshot(
    val subsystems: List<LifeOsSubsystemStatus>,
    val capabilityProviderCount: Int,
    val generatedProviderCount: Int,
) {
    val registeredSubsystemCount: Int get() = subsystems.size
    val operationalSubsystemCount: Int
        get() = subsystems.count { it.state == LifeOsSubsystemState.ACTIVE || it.state == LifeOsSubsystemState.DEGRADED }
    val unavailableSubsystems: List<LifeOsSubsystemStatus>
        get() = subsystems.filter { it.state == LifeOsSubsystemState.UNAVAILABLE }
    val unboundSubsystems: List<LifeOsSubsystemStatus>
        get() = subsystems.filter { it.state == LifeOsSubsystemState.REGISTERED }
    val fullyConnected: Boolean get() = unavailableSubsystems.isEmpty() && unboundSubsystems.isEmpty()
    val fullyOperational: Boolean get() = subsystems.all { it.state == LifeOsSubsystemState.ACTIVE }
}

/**
 * Process-wide topology projection for the productive LIFEOS runtime.
 * Capability availability comes exclusively from CapabilityRegistry. Runtime presence and lifecycle
 * come from LifeOsRuntimeBindingRegistry. Dependencies are propagated in deterministic topological
 * order so downstream nodes cannot appear healthy when an upstream subsystem is absent.
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
        LifeOsSubsystemDescriptor(
            "word-field-understanding",
            requiredCapabilities = setOf(MultimodalPerceptionCapabilities.WORD_FIELD),
            dependencies = setOf("language-understanding"),
        ),
        LifeOsSubsystemDescriptor(
            "speech-field-recognition",
            requiredCapabilities = setOf(MultimodalPerceptionCapabilities.SPEECH_FIELD),
            dependencies = setOf("word-field-understanding"),
        ),
        LifeOsSubsystemDescriptor(
            "writing-field-recognition",
            requiredCapabilities = setOf(MultimodalPerceptionCapabilities.WRITING_FIELD),
            dependencies = setOf("word-field-understanding"),
        ),
        LifeOsSubsystemDescriptor("language-context", dependencies = setOf("photon-store")),
        LifeOsSubsystemDescriptor("capability-registry"),
        LifeOsSubsystemDescriptor("capability-router", dependencies = setOf("capability-registry")),
        LifeOsSubsystemDescriptor("owner-policy"),
        LifeOsSubsystemDescriptor("resource-intelligence"),
        LifeOsSubsystemDescriptor("resource-budgets", dependencies = setOf("resource-intelligence")),
        LifeOsSubsystemDescriptor("decision-trace", dependencies = setOf("photon-store")),
        LifeOsSubsystemDescriptor("goal-planning", dependencies = setOf("language-understanding", "capability-router")),
        LifeOsSubsystemDescriptor("goal-resume", requiredCapabilities = setOf("goal.resume"), dependencies = setOf("goal-planning")),
        LifeOsSubsystemDescriptor("local-knowledge", requiredCapabilities = setOf("knowledge.resolve"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("deep-search", requiredCapabilities = setOf("deepsearch.query"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("scene-compiler", requiredCapabilities = setOf("scene.construct.procedural"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("scene-rasterizer", requiredCapabilities = setOf("scene.rasterize.mmsi"), dependencies = setOf("scene-compiler")),
        LifeOsSubsystemDescriptor("image-renderer", requiredCapabilities = setOf("image.render.mmsi"), dependencies = setOf("scene-rasterizer")),
        LifeOsSubsystemDescriptor("image-transform", requiredCapabilities = setOf("image.transform.mmsi"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("reminder-scheduler", requiredCapabilities = setOf("planner.schedule"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("communication", requiredCapabilities = setOf("communication.dispatch"), dependencies = setOf("capability-router")),
        LifeOsSubsystemDescriptor("durable-task-engine", dependencies = setOf("photon-store")),
        LifeOsSubsystemDescriptor("continuous-cognition", dependencies = setOf("photon-store", "durable-task-engine")),
        LifeOsSubsystemDescriptor("cognition-reconciler", dependencies = setOf("continuous-cognition")),
        LifeOsSubsystemDescriptor("cognition-outcome-pipeline", dependencies = setOf("continuous-cognition")),
        LifeOsSubsystemDescriptor("cognitive-worker", dependencies = setOf("durable-task-engine", "field-runtime")),
        LifeOsSubsystemDescriptor("task-scheduler", dependencies = setOf("durable-task-engine", "cognitive-worker")),
        LifeOsSubsystemDescriptor("lease-recovery", dependencies = setOf("durable-task-engine")),
        LifeOsSubsystemDescriptor("runtime-supervisor", dependencies = setOf("task-scheduler", "lease-recovery")),
        LifeOsSubsystemDescriptor("health-graph"),
        LifeOsSubsystemDescriptor("protection-coordinator", dependencies = setOf("health-graph")),
        LifeOsSubsystemDescriptor("self-healing", dependencies = setOf("health-graph", "runtime-supervisor", "resource-budgets")),
        LifeOsSubsystemDescriptor("tool-workshop", dependencies = setOf("capability-registry", "resource-budgets", "owner-policy")),
        LifeOsSubsystemDescriptor("generated-tool-registry", dependencies = setOf("tool-workshop", "capability-registry")),
        LifeOsSubsystemDescriptor("autonomous-tool-workshop", dependencies = setOf("tool-workshop", "continuous-cognition")),
        LifeOsSubsystemDescriptor("evolution-hot-swap", dependencies = setOf("generated-tool-registry", "capability-registry", "owner-policy")),
        LifeOsSubsystemDescriptor("hot-swap-runtime", dependencies = setOf("evolution-hot-swap", "owner-policy", "resource-budgets")),
        LifeOsSubsystemDescriptor("learning-adaptation", dependencies = setOf("field-runtime", "capability-router")),
        LifeOsSubsystemDescriptor("build-studio", requiredCapabilities = setOf("buildstudio.run"), dependencies = setOf("capability-router", "tool-workshop")),
    ).also { descriptors ->
        require(descriptors.size >= MINIMUM_CANONICAL_SUBSYSTEMS) {
            "Canonical LIFEOS topology must not shrink below the established subsystem baseline"
        }
        require(descriptors.map { it.id }.distinct().size == descriptors.size) {
            "Canonical LIFEOS subsystem ids must be unique"
        }
        val seen = linkedSetOf<String>()
        descriptors.forEach { descriptor ->
            require(seen.containsAll(descriptor.dependencies)) {
                "LIFEOS topology must be topologically ordered; ${descriptor.id} depends on ${descriptor.dependencies - seen}"
            }
            seen += descriptor.id
        }
    }

    suspend fun snapshot(): LifeOsRuntimeTopologySnapshot? {
        val registry = GeneratedToolRuntimeProcessRegistry.capabilities() ?: return null
        val providers = registry.all(includeUnavailable = true)
        val providersByCapability = providers.groupBy { it.capabilityId.value }
        val bindings = LifeOsRuntimeBindingRegistry.snapshot()
        val resolved = linkedMapOf<String, LifeOsSubsystemStatus>()

        canonicalSubsystems.forEach { descriptor ->
            val matching = descriptor.requiredCapabilities.flatMap { providersByCapability[it].orEmpty() }
            val unavailableCapabilities = descriptor.requiredCapabilities.filterTo(linkedSetOf()) { capability ->
                providersByCapability[capability].orEmpty().none { provider ->
                    provider.state == ProviderState.ACTIVE || provider.state == ProviderState.DEGRADED
                }
            }
            val activeProviders = matching
                .filter { it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED }
                .mapTo(linkedSetOf()) { it.providerId }
            val binding = bindings[descriptor.id]
            var state = when {
                unavailableCapabilities.isNotEmpty() -> LifeOsSubsystemState.UNAVAILABLE
                binding == null -> LifeOsSubsystemState.REGISTERED
                binding.state == LifeOsRuntimeBindingState.REGISTERED -> LifeOsSubsystemState.REGISTERED
                binding.state == LifeOsRuntimeBindingState.ACTIVE && matching.any { it.state == ProviderState.DEGRADED } ->
                    LifeOsSubsystemState.DEGRADED
                binding.state == LifeOsRuntimeBindingState.ACTIVE -> LifeOsSubsystemState.ACTIVE
                binding.state == LifeOsRuntimeBindingState.DEGRADED -> LifeOsSubsystemState.DEGRADED
                binding.state == LifeOsRuntimeBindingState.QUARANTINED || binding.state == LifeOsRuntimeBindingState.STOPPED ->
                    LifeOsSubsystemState.UNAVAILABLE
                else -> LifeOsSubsystemState.REGISTERED
            }

            val unavailableDependencies = descriptor.dependencies.filterTo(linkedSetOf()) { dependency ->
                val dependencyState = resolved[dependency]?.state ?: LifeOsSubsystemState.UNAVAILABLE
                dependencyState == LifeOsSubsystemState.UNAVAILABLE || dependencyState == LifeOsSubsystemState.REGISTERED
            }
            val degradedDependency = descriptor.dependencies.any { dependency ->
                resolved[dependency]?.state == LifeOsSubsystemState.DEGRADED
            }
            if (unavailableDependencies.isNotEmpty()) {
                state = LifeOsSubsystemState.UNAVAILABLE
            } else if (degradedDependency && state == LifeOsSubsystemState.ACTIVE) {
                state = LifeOsSubsystemState.DEGRADED
            }

            resolved[descriptor.id] = LifeOsSubsystemStatus(
                descriptor = descriptor,
                state = state,
                binding = binding,
                activeProviderIds = activeProviders,
                unavailableCapabilities = unavailableCapabilities,
                unavailableDependencies = unavailableDependencies,
            )
        }

        return LifeOsRuntimeTopologySnapshot(
            subsystems = resolved.values.toList(),
            capabilityProviderCount = providers.size,
            generatedProviderCount = providers.count { it.providerType == ProviderType.GENERATED_TOOL },
        )
    }

    const val MINIMUM_CANONICAL_SUBSYSTEMS = 36
}
