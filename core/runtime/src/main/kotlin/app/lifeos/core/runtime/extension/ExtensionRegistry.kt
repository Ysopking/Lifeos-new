package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds

data class ExtensionRevisionRef(
    val extensionId: ExtensionId,
    val version: ExtensionVersion,
) {
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-revision-ref/v1",
        extensionId.value,
        version.value,
    )
}

data class ExtensionRegistryEntry(
    val manifest: ExtensionManifest,
    val worldContract: ExtensionWorldContract,
    val dependencies: Set<ExtensionRevisionRef> = emptySet(),
) {
    val ref: ExtensionRevisionRef = ExtensionRevisionRef(
        extensionId = manifest.extensionId,
        version = manifest.version,
    )

    init {
        require(ref !in dependencies) { "Extension cannot depend on itself" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-registry-entry/v1",
        manifest.fingerprint(),
        worldContract.worldSignalSchemaVersion.major.toString(),
        worldContract.worldSignalSchemaVersion.minor.toString(),
        worldContract.worldNodeSchemaVersion.major.toString(),
        worldContract.worldNodeSchemaVersion.minor.toString(),
        worldContract.worldEquationVersion.id,
        worldContract.worldEquationVersion.major.toString(),
        worldContract.worldEquationVersion.minor.toString(),
        worldContract.coefficientSchemaFingerprint.value,
        worldContract.projectionContractFingerprint.value,
        *dependencies
            .sortedWith(compareBy<ExtensionRevisionRef>({ it.extensionId.value }, { it.version.value }))
            .map { it.fingerprint() }
            .toTypedArray(),
    )
}

data class ExtensionRegistrySnapshot private constructor(
    val id: String,
    val entries: List<ExtensionRegistryEntry>,
    val topologicalOrder: List<ExtensionRevisionRef>,
) {
    init {
        require(id.isNotBlank())
        require(entries.isNotEmpty())
        require(entries.map { it.manifest.extensionId }.distinct().size == entries.size) {
            "Extension registry snapshot may select only one version per extension id"
        }
        require(topologicalOrder.size == entries.size)
        require(topologicalOrder.toSet().size == entries.size)
        require(topologicalOrder.toSet() == entries.mapTo(linkedSetOf()) { it.ref })
        require(id == expectedId()) { "Extension registry snapshot id does not match content" }
    }

    /**
     * B148 is an immutable topology snapshot. It cannot activate or promote extensions.
     */
    val directActivationAllowed: Boolean
        get() = false

    fun resolve(extensionId: ExtensionId): ExtensionRegistryEntry? =
        entries.firstOrNull { it.manifest.extensionId == extensionId }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-registry-snapshot/v1",
        *entries
            .sortedWith(
                compareBy<ExtensionRegistryEntry>(
                    { it.manifest.extensionId.value },
                    { it.manifest.version.value },
                ),
            )
            .map { it.fingerprint() }
            .toTypedArray(),
        *topologicalOrder.map { "order:${it.fingerprint()}" }.toTypedArray(),
    )

    private fun expectedId(): String = "extension-registry:${fingerprint()}"

    companion object {
        fun create(entries: Collection<ExtensionRegistryEntry>): ExtensionRegistrySnapshot {
            require(entries.isNotEmpty()) { "Extension registry snapshot requires at least one entry" }

            val byId = linkedMapOf<ExtensionId, ExtensionRegistryEntry>()
            entries
                .sortedWith(
                    compareBy<ExtensionRegistryEntry>(
                        { it.manifest.extensionId.value },
                        { it.manifest.version.value },
                    ),
                )
                .forEach { entry ->
                    require(byId.put(entry.manifest.extensionId, entry) == null) {
                        "Extension registry snapshot cannot select multiple versions of ${entry.manifest.extensionId}"
                    }
                }

            val byRef = byId.values.associateBy { it.ref }
            byId.values.forEach { entry ->
                val missing = entry.dependencies.filterNot { it in byRef }
                require(missing.isEmpty()) {
                    "Extension ${entry.ref} has missing dependencies: $missing"
                }
            }

            val order = deterministicTopologicalOrder(byId.values.toList(), byRef)
            val provisional = ExtensionRegistrySnapshot(
                id = "pending",
                entries = byId.values.toList(),
                topologicalOrder = order,
            )
            val fingerprint = StableFieldIds.fingerprint(
                "extension-registry-snapshot/v1",
                *provisional.entries
                    .map { it.fingerprint() }
                    .toTypedArray(),
                *order.map { "order:${it.fingerprint()}" }.toTypedArray(),
            )
            return ExtensionRegistrySnapshot(
                id = "extension-registry:$fingerprint",
                entries = provisional.entries,
                topologicalOrder = order,
            )
        }

        private fun deterministicTopologicalOrder(
            entries: List<ExtensionRegistryEntry>,
            byRef: Map<ExtensionRevisionRef, ExtensionRegistryEntry>,
        ): List<ExtensionRevisionRef> {
            val indegree = entries.associate { it.ref to it.dependencies.size }.toMutableMap()
            val dependents = entries.associate { it.ref to mutableListOf<ExtensionRevisionRef>() }.toMutableMap()

            entries.forEach { entry ->
                entry.dependencies.forEach { dependency ->
                    require(dependency in byRef)
                    dependents.getValue(dependency) += entry.ref
                }
            }

            val ready = java.util.PriorityQueue(
                compareBy<ExtensionRevisionRef>({ it.extensionId.value }, { it.version.value }),
            )
            indegree.filterValues { it == 0 }.keys.forEach(ready::add)

            val order = mutableListOf<ExtensionRevisionRef>()
            while (ready.isNotEmpty()) {
                val next = ready.remove()
                order += next
                dependents.getValue(next)
                    .sortedWith(compareBy<ExtensionRevisionRef>({ it.extensionId.value }, { it.version.value }))
                    .forEach { dependent ->
                        val remaining = requireNotNull(indegree[dependent]) - 1
                        indegree[dependent] = remaining
                        if (remaining == 0) ready += dependent
                    }
            }

            require(order.size == entries.size) {
                "Extension dependency graph must be acyclic"
            }
            return order
        }
    }
}
