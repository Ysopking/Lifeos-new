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

/** Compatibility/read-model projection. The authoritative topology definition is SubsystemManifest. */
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
    val manifestFingerprint: String,
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
 * come from LifeOsRuntimeBindingRegistry. The typed manifest graph is the single topology source;
 * descriptor order, dependency propagation and configuration identity are derived from it.
 */
object LifeOsProcessTopology {
    val canonicalManifestGraph: SubsystemManifestGraph = SubsystemManifestGraph(
        listOf(
            manifest("photon-store"),
            manifest("binary-asset-store"),
            manifest("thought-matrix"),
            manifest("thought-graph", dependencies = setOf("photon-store")),
            manifest("field-runtime", dependencies = setOf("thought-matrix")),
            manifest("field-thought-graph-projection", dependencies = setOf("field-runtime", "thought-graph")),
            manifest("world-formula", dependencies = setOf("field-runtime")),
            manifest("language-understanding", requiredCapabilities = setOf("language.understand")),
            manifest("language-context", dependencies = setOf("photon-store")),
            manifest("capability-registry"),
            manifest("capability-router", dependencies = setOf("capability-registry")),
            manifest("owner-policy", startupOwner = SubsystemStartupOwner.SHARED_RESOURCES),
            manifest("resource-intelligence", startupOwner = SubsystemStartupOwner.SHARED_RESOURCES),
            manifest(
                "resource-budgets",
                dependencies = setOf("resource-intelligence"),
                startupOwner = SubsystemStartupOwner.SHARED_RESOURCES,
            ),
            manifest(
                "decision-trace",
                dependencies = setOf("photon-store"),
                startupOwner = SubsystemStartupOwner.SHARED_RESOURCES,
            ),
            manifest(
                "goal-planning",
                dependencies = setOf("language-understanding", "capability-router"),
                startupOwner = SubsystemStartupOwner.DURABLE_GOALS,
            ),
            manifest(
                "goal-resume",
                requiredCapabilities = setOf("goal.resume"),
                dependencies = setOf("goal-planning"),
            ),
            manifest(
                "local-knowledge",
                requiredCapabilities = setOf("knowledge.resolve"),
                dependencies = setOf("capability-router"),
            ),
            manifest(
                "deep-search",
                requiredCapabilities = setOf("deepsearch.query"),
                dependencies = setOf("capability-router"),
                startupOwner = SubsystemStartupOwner.DEEP_SEARCH,
            ),
            manifest(
                "scene-compiler",
                requiredCapabilities = setOf("scene.construct.procedural"),
                dependencies = setOf("capability-router"),
            ),
            manifest(
                "scene-rasterizer",
                requiredCapabilities = setOf("scene.rasterize.mmsi"),
                dependencies = setOf("scene-compiler"),
            ),
            manifest(
                "image-renderer",
                requiredCapabilities = setOf("image.render.mmsi"),
                dependencies = setOf("scene-rasterizer"),
            ),
            manifest(
                "image-transform",
                requiredCapabilities = setOf("image.transform.mmsi"),
                dependencies = setOf("capability-router"),
            ),
            manifest(
                "reminder-scheduler",
                requiredCapabilities = setOf("planner.schedule"),
                dependencies = setOf("capability-router"),
            ),
            manifest(
                "communication",
                requiredCapabilities = setOf("communication.dispatch"),
                dependencies = setOf("capability-router"),
            ),
            manifest("durable-task-engine", dependencies = setOf("photon-store")),
            manifest("continuous-cognition", dependencies = setOf("photon-store", "durable-task-engine")),
            manifest("cognition-reconciler", dependencies = setOf("continuous-cognition")),
            manifest("cognition-outcome-pipeline", dependencies = setOf("continuous-cognition")),
            manifest("cognitive-worker", dependencies = setOf("durable-task-engine", "field-runtime")),
            manifest("task-scheduler", dependencies = setOf("durable-task-engine", "cognitive-worker")),
            manifest("lease-recovery", dependencies = setOf("durable-task-engine")),
            manifest("runtime-supervisor", dependencies = setOf("task-scheduler", "lease-recovery")),
            manifest("health-graph"),
            manifest("protection-coordinator", dependencies = setOf("health-graph")),
            manifest(
                "self-healing",
                dependencies = setOf("health-graph", "runtime-supervisor", "resource-budgets"),
                startupOwner = SubsystemStartupOwner.SELF_HEALING,
            ),
            manifest(
                "tool-workshop",
                dependencies = setOf("capability-registry", "resource-budgets", "owner-policy"),
            ),
            manifest("generated-tool-registry", dependencies = setOf("tool-workshop", "capability-registry")),
            manifest("autonomous-tool-workshop", dependencies = setOf("tool-workshop", "continuous-cognition")),
            manifest(
                "evolution-hot-swap",
                dependencies = setOf("generated-tool-registry", "capability-registry", "owner-policy"),
            ),
            manifest(
                "hot-swap-runtime",
                dependencies = setOf("evolution-hot-swap", "owner-policy", "resource-budgets"),
                startupOwner = SubsystemStartupOwner.OPTIONAL_RUNTIME,
            ),
            manifest("learning-adaptation", dependencies = setOf("field-runtime", "capability-router")),
            manifest(
                "build-studio",
                requiredCapabilities = setOf("buildstudio.run"),
                dependencies = setOf("capability-router", "tool-workshop"),
                startupOwner = SubsystemStartupOwner.EXTERNAL_HOST,
            ),
        )
    ).also { graph ->
        require(graph.topologicalOrder.size >= MINIMUM_CANONICAL_SUBSYSTEMS) {
            "Canonical LIFEOS topology must not shrink below the established subsystem baseline"
        }
    }

    val canonicalSubsystems: List<LifeOsSubsystemDescriptor> =
        canonicalManifestGraph.topologicalOrder.map(SubsystemManifest::descriptor)

    val manifestFingerprint: String get() = canonicalManifestGraph.fingerprint

    fun isKnownSubsystem(id: SubsystemId): Boolean = canonicalManifestGraph.contains(id)

    fun manifest(id: SubsystemId): SubsystemManifest = canonicalManifestGraph.requireManifest(id)

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
            manifestFingerprint = manifestFingerprint,
        )
    }

    private fun manifest(
        id: String,
        requiredCapabilities: Set<String> = emptySet(),
        dependencies: Set<String> = emptySet(),
        startupOwner: SubsystemStartupOwner = SubsystemStartupOwner.KERNEL_GRAPH,
        version: String = "1",
    ): SubsystemManifest = SubsystemManifest(
        id = SubsystemId(id),
        requiredCapabilities = requiredCapabilities,
        dependencies = dependencies.mapTo(linkedSetOf(), ::SubsystemId),
        startupOwner = startupOwner,
        version = version,
    )

    const val MINIMUM_CANONICAL_SUBSYSTEMS = 36
}
