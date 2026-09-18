package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.extension.ExtensionPointKind
import app.lifeos.core.runtime.extension.ExtensionPointRegistration
import app.lifeos.core.runtime.extension.ExtensionPointSnapshot
import app.lifeos.core.runtime.extension.WorldModelProjectionProvider

enum class WorldSemanticRelationKind {
    DERIVED_FROM,
    SUPPORTS,
    CONTRADICTS,
    TARGETS_GOAL,
    CAUSES,
    GENERALIZES_TO,
    ANALOGOUS_TO,
    PREDICTS,
}

data class WorldSemanticRelation(
    val source: WorldTargetRef,
    val target: WorldTargetRef,
    val kind: WorldSemanticRelationKind,
    val strength: Double,
    val provenanceFingerprint: String,
    val explanation: String,
) {
    init {
        require(source != target) { "World semantic relation must connect distinct targets" }
        require(strength.isFinite() && strength in 0.0..1.0)
        require(provenanceFingerprint.isNotBlank())
        require(explanation.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-semantic-relation/v1",
        source.fingerprint(),
        target.fingerprint(),
        kind.name,
        java.lang.Double.toHexString(strength),
        provenanceFingerprint,
        explanation,
    )
}

enum class WorldProjectionSourceKind {
    FIELD,
    THOUGHT_GRAPH,
    MEMORY,
    GOAL_PLAN,
    STRATEGY,
    MODULE,
    CAPABILITY,
    HEALTH,
    RESOURCE,
    OUTCOME,
    EXTENSION,
}

data class WorldProjectionDescriptor(
    val providerId: String,
    val sourceKind: WorldProjectionSourceKind,
    val nodeKinds: Set<WorldNodeKind>,
    val signalDimensions: Set<WorldSignalDimension>,
    val relationKinds: Set<WorldSemanticRelationKind> = emptySet(),
    val extensionPointSnapshotId: String? = null,
    val contractFingerprint: String? = null,
) {
    init {
        require(providerId.isNotBlank())
        require(nodeKinds.isNotEmpty())
        require(signalDimensions.isNotEmpty())
        require(extensionPointSnapshotId == null || extensionPointSnapshotId.isNotBlank())
        require(contractFingerprint == null || contractFingerprint.isNotBlank())
        if (sourceKind == WorldProjectionSourceKind.EXTENSION) {
            require(extensionPointSnapshotId != null)
            require(contractFingerprint != null)
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-projection-descriptor/v1",
        providerId,
        sourceKind.name,
        extensionPointSnapshotId.orEmpty(),
        contractFingerprint.orEmpty(),
        *nodeKinds.map { "node:${it.name}" }.sorted().toTypedArray(),
        *signalDimensions.map { "signal:${it.name}" }.sorted().toTypedArray(),
        *relationKinds.map { "relation:${it.name}" }.sorted().toTypedArray(),
    )
}

data class WorldProjectionContext(
    val frozenSourceSnapshots: Map<String, String>,
    val inputs: List<WorldFormulaInputSnapshot>,
) {
    init {
        require(frozenSourceSnapshots.isNotEmpty())
        require(frozenSourceSnapshots.keys.none { it.isBlank() })
        require(frozenSourceSnapshots.values.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-projection-context/v1",
        *buildList {
            frozenSourceSnapshots.toSortedMap().forEach { (key, value) ->
                add("source:$key:$value")
            }
            inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key }))
                .forEach { add("input:${it.fingerprint()}") }
        }.toTypedArray(),
    )
}

data class UniversalWorldProjection(
    val providerId: String,
    val contextFingerprint: String,
    val inputs: List<WorldFormulaInputSnapshot>,
    val relations: List<WorldSemanticRelation>,
) {
    init {
        require(providerId.isNotBlank())
        require(contextFingerprint.isNotBlank())
        require(inputs.map { it.target }.distinct().size == inputs.size)
        require(inputs == inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key })))
        require(relations.map { it.fingerprint() }.distinct().size == relations.size)
        require(relations == relations.sortedBy { it.fingerprint() })
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val equationActivationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "universal-world-projection/v1",
        providerId,
        contextFingerprint,
        *inputs.map { it.fingerprint() }.toTypedArray(),
        *relations.map { it.fingerprint() }.toTypedArray(),
    )
}

interface UniversalWorldProjectionProvider {
    val descriptor: WorldProjectionDescriptor

    fun project(context: WorldProjectionContext): UniversalWorldProjection
}

class ExtensionWorldProjectionAdapter(
    private val extensionPoints: ExtensionPointSnapshot,
    private val registration: ExtensionPointRegistration,
    private val delegate: WorldModelProjectionProvider,
    nodeKinds: Set<WorldNodeKind>,
    signalDimensions: Set<WorldSignalDimension>,
) : UniversalWorldProjectionProvider {
    override val descriptor: WorldProjectionDescriptor

    init {
        require(registration.point == ExtensionPointKind.WORLD_MODEL_PROJECTION)
        require(registration in extensionPoints.registrations)
        require(delegate.providerId == registration.providerId)
        require(delegate.contractFingerprint.value == registration.contractFingerprint)
        descriptor = WorldProjectionDescriptor(
            providerId = delegate.providerId,
            sourceKind = WorldProjectionSourceKind.EXTENSION,
            nodeKinds = nodeKinds,
            signalDimensions = signalDimensions,
            extensionPointSnapshotId = extensionPoints.id,
            contractFingerprint = registration.contractFingerprint,
        )
    }

    override fun project(context: WorldProjectionContext): UniversalWorldProjection {
        val projected = delegate.project(context.inputs)
            .distinctBy { it.target }
            .sortedWith(compareBy({ it.target.kind.name }, { it.target.key }))
        return UniversalWorldProjection(
            providerId = descriptor.providerId,
            contextFingerprint = context.fingerprint(),
            inputs = projected,
            relations = emptyList(),
        )
    }
}

data class WorldProjectionRegistrySnapshot private constructor(
    val id: String,
    val descriptors: List<WorldProjectionDescriptor>,
) {
    init {
        require(descriptors.isNotEmpty())
        require(descriptors.map { it.providerId }.distinct().size == descriptors.size)
        require(descriptors == descriptors.sortedBy { it.providerId })
        require(id == "world-projection-registry:${fingerprint()}")
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-projection-registry/v1",
        *descriptors.map { it.fingerprint() }.toTypedArray(),
    )

    companion object {
        fun create(descriptors: Collection<WorldProjectionDescriptor>): WorldProjectionRegistrySnapshot {
            val canonical = descriptors.sortedBy { it.providerId }
            require(canonical.isNotEmpty())
            require(canonical.map { it.providerId }.distinct().size == canonical.size)
            val fingerprint = StableFieldIds.fingerprint(
                "world-projection-registry/v1",
                *canonical.map { it.fingerprint() }.toTypedArray(),
            )
            return WorldProjectionRegistrySnapshot(
                id = "world-projection-registry:$fingerprint",
                descriptors = canonical,
            )
        }
    }
}

/**
 * B163 read-only projection registry. It routes frozen source snapshots into typed candidate
 * projections only; productive WorldFormula/WorldHead publication remains owned by B161/B162.
 */
class WorldProjectionRegistry(
    providers: Collection<UniversalWorldProjectionProvider>,
) {
    private val byId = providers.associateBy { it.descriptor.providerId }.also {
        require(it.size == providers.size) { "World projection provider ids must be unique" }
    }

    val snapshot: WorldProjectionRegistrySnapshot =
        WorldProjectionRegistrySnapshot.create(byId.values.map { it.descriptor })

    fun descriptor(providerId: String): WorldProjectionDescriptor? =
        byId[providerId]?.descriptor

    fun project(
        providerId: String,
        context: WorldProjectionContext,
    ): UniversalWorldProjection {
        val provider = requireNotNull(byId[providerId]) {
            "Unknown world projection provider: $providerId"
        }
        val projection = provider.project(context)
        require(projection.providerId == providerId)
        require(projection.contextFingerprint == context.fingerprint())
        return projection
    }
}
