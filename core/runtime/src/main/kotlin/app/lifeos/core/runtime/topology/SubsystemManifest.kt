package app.lifeos.core.runtime.topology

import app.lifeos.core.field.StableFieldIds

@JvmInline
value class SubsystemId(val value: String) {
    init {
        require(value.isNotBlank()) { "Subsystem id must not be blank" }
        require(value == value.trim()) { "Subsystem id must not contain surrounding whitespace" }
        require(value.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
            "Subsystem id must use lowercase kebab-case: $value"
        }
    }

    override fun toString(): String = value
}

enum class SubsystemStartupOwner {
    SHARED_RESOURCES,
    KERNEL_GRAPH,
    DEEP_SEARCH,
    SELF_HEALING,
    DURABLE_GOALS,
    OPTIONAL_RUNTIME,
    EXTERNAL_HOST,
}

data class SubsystemManifest(
    val id: SubsystemId,
    val requiredCapabilities: Set<String> = emptySet(),
    val dependencies: Set<SubsystemId> = emptySet(),
    val startupOwner: SubsystemStartupOwner,
    val version: String = "1",
) {
    init {
        require(requiredCapabilities.none { it.isBlank() }) {
            "Subsystem capabilities must not be blank"
        }
        require(id !in dependencies) { "Subsystem ${id.value} cannot depend on itself" }
        require(version.isNotBlank()) { "Subsystem manifest version must not be blank" }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "lifeos-subsystem-manifest/v1",
        id.value,
        startupOwner.name,
        version,
        *requiredCapabilities.sorted().map { "capability:$it" }.toTypedArray(),
        *dependencies.sortedBy { it.value }.map { "dependency:${it.value}" }.toTypedArray(),
    )

    fun descriptor(): LifeOsSubsystemDescriptor = LifeOsSubsystemDescriptor(
        id = id.value,
        requiredCapabilities = requiredCapabilities,
        dependencies = dependencies.mapTo(linkedSetOf()) { it.value },
    )
}

/**
 * Immutable validated graph for the productive runtime topology. Input declaration order never
 * affects layers, topological order or the graph fingerprint.
 */
class SubsystemManifestGraph(manifests: Collection<SubsystemManifest>) {
    private val byId: Map<SubsystemId, SubsystemManifest>
    val startupLayers: List<List<SubsystemManifest>>
    val topologicalOrder: List<SubsystemManifest>
    val fingerprint: String

    init {
        require(manifests.isNotEmpty()) { "Subsystem manifest graph must not be empty" }
        val grouped = manifests.groupBy { it.id }
        val duplicates = grouped.filterValues { it.size > 1 }.keys.sortedBy { it.value }
        require(duplicates.isEmpty()) {
            "Duplicate subsystem ids: ${duplicates.joinToString { it.value }}"
        }
        byId = grouped.mapValues { (_, entries) -> entries.single() }

        val missing = manifests.flatMap { manifest ->
            manifest.dependencies
                .filterNot(byId::containsKey)
                .map { dependency -> "${manifest.id.value}->${dependency.value}" }
        }.sorted()
        require(missing.isEmpty()) {
            "Missing subsystem dependencies: ${missing.joinToString()}"
        }

        startupLayers = computeLayers()
        topologicalOrder = startupLayers.flatten()
        require(topologicalOrder.size == byId.size) {
            val resolved = topologicalOrder.mapTo(linkedSetOf()) { it.id }
            val cyclic = byId.keys.filterNot(resolved::contains).sortedBy { it.value }
            "Cyclic subsystem dependencies: ${cyclic.joinToString { it.value }}"
        }
        fingerprint = StableFieldIds.fingerprint(
            "lifeos-subsystem-manifest-graph/v1",
            *topologicalOrder.map { it.fingerprint }.toTypedArray(),
        )
    }

    fun contains(id: SubsystemId): Boolean = byId.containsKey(id)

    fun requireManifest(id: SubsystemId): SubsystemManifest = requireNotNull(byId[id]) {
        "Unknown LIFEOS subsystem: ${id.value}"
    }

    fun manifestsFor(owner: SubsystemStartupOwner): List<SubsystemManifest> =
        topologicalOrder.filter { it.startupOwner == owner }

    fun ids(): Set<SubsystemId> = byId.keys.toSet()

    private fun computeLayers(): List<List<SubsystemManifest>> {
        val remaining = byId.keys.toMutableSet()
        val resolved = linkedSetOf<SubsystemId>()
        val layers = mutableListOf<List<SubsystemManifest>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining
                .asSequence()
                .map(byId::getValue)
                .filter { manifest -> resolved.containsAll(manifest.dependencies) }
                .sortedBy { it.id.value }
                .toList()
            require(ready.isNotEmpty()) {
                val cyclic = remaining.sortedBy { it.value }
                "Cyclic subsystem dependencies: ${cyclic.joinToString { it.value }}"
            }
            layers += ready
            ready.forEach { manifest ->
                resolved += manifest.id
                remaining -= manifest.id
            }
        }
        return layers
    }
}
