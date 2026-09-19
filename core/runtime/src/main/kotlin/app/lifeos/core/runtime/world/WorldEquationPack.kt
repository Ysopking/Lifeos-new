package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension

data class WorldInteractionSchemaEntry(
    val coefficientId: WorldCoefficientId,
    val sourceNodeKinds: Set<WorldNodeKind>,
    val targetNodeKinds: Set<WorldNodeKind>,
) {
    init {
        require(sourceNodeKinds.isNotEmpty())
        require(targetNodeKinds.isNotEmpty())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-interaction-schema-entry/v1",
        coefficientId.value,
        *sourceNodeKinds.map { "source-node:" + it.name }.sorted().toTypedArray(),
        *targetNodeKinds.map { "target-node:" + it.name }.sorted().toTypedArray(),
    )
}

data class WorldInteractionSchema(
    val version: String,
    val entries: List<WorldInteractionSchemaEntry>,
) {
    init {
        require(version.isNotBlank())
        require(entries.isNotEmpty())
        require(entries.map { it.coefficientId }.distinct().size == entries.size) {
            "World interaction schema coefficient ids must be unique"
        }
    }

    fun stableEntries(): List<WorldInteractionSchemaEntry> =
        entries.sortedBy { it.coefficientId.value }

    fun coefficientIds(): Set<WorldCoefficientId> =
        stableEntries().mapTo(linkedSetOf()) { it.coefficientId }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-interaction-schema/v1",
        version,
        *stableEntries().map { it.fingerprint() }.toTypedArray(),
    )
}

data class WorldProjectionContractSnapshot private constructor(
    val registrySnapshotId: String,
    val registryFingerprint: String,
    val requiredProviderIds: Set<String>,
) {
    init {
        require(registrySnapshotId.isNotBlank())
        require(registryFingerprint.isNotBlank())
        require(requiredProviderIds.isNotEmpty())
        require(requiredProviderIds.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-projection-contract-snapshot/v1",
        registrySnapshotId,
        registryFingerprint,
        *requiredProviderIds.sorted().toTypedArray(),
    )

    companion object {
        internal fun restore(
            registrySnapshotId: String,
            registryFingerprint: String,
            requiredProviderIds: Set<String>,
        ): WorldProjectionContractSnapshot = WorldProjectionContractSnapshot(
            registrySnapshotId = registrySnapshotId,
            registryFingerprint = registryFingerprint,
            requiredProviderIds = requiredProviderIds,
        )

        fun create(
            registry: WorldProjectionRegistrySnapshot,
            requiredProviderIds: Set<String>,
        ): WorldProjectionContractSnapshot {
            require(requiredProviderIds.isNotEmpty())
            val available = registry.descriptors.mapTo(linkedSetOf()) { it.providerId }
            require(requiredProviderIds.all { it in available }) {
                "World projection contract references provider outside frozen registry snapshot"
            }
            return WorldProjectionContractSnapshot(
                registrySnapshotId = registry.id,
                registryFingerprint = registry.fingerprint(),
                requiredProviderIds = requiredProviderIds.toSet(),
            )
        }
    }
}

enum class WorldEquationPackChangeKind {
    PARAMETER_ONLY,
    STRUCTURAL,
}

/**
 * Versioned structural envelope around one WorldEquationSpec.
 *
 * V1 deliberately carries no productive activation authority. The existing WorldEquation
 * activation path remains parameter-only until a later structural admission stack can prove
 * projection, interaction and dimension compatibility independently.
 */
data class WorldEquationPack(
    val version: String,
    val equation: WorldEquationSpec,
    val interactionSchema: WorldInteractionSchema,
    val projectionContract: WorldProjectionContractSnapshot,
    val requiredDimensions: Set<WorldSignalDimension>,
    val requiredNodeKinds: Set<WorldNodeKind>,
) {
    init {
        require(version.isNotBlank())
        require(requiredDimensions.isNotEmpty())
        require(requiredNodeKinds.isNotEmpty())

        val equationCoefficients = equation.stableCoefficients()
        val equationIds = equationCoefficients.mapTo(linkedSetOf()) { it.id }
        require(interactionSchema.coefficientIds() == equationIds) {
            "WorldEquationPack interaction schema must cover exactly the equation coefficients"
        }

        val equationDimensions = equationCoefficients
            .flatMap { listOf(it.sourceDimension, it.targetDimension) }
            .toSet()
        require(requiredDimensions.containsAll(equationDimensions)) {
            "WorldEquationPack required dimensions omit an equation dimension"
        }

        require(
            interactionSchema.stableEntries().all { entry ->
                entry.sourceNodeKinds.all { it in requiredNodeKinds } &&
                    entry.targetNodeKinds.all { it in requiredNodeKinds }
            }
        ) {
            "WorldEquationPack interaction schema uses undeclared node kinds"
        }
    }

    val productiveActivationAllowed: Boolean
        get() = false

    fun structuralFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structure/v1",
        equation.schemaFingerprint(),
        interactionSchema.fingerprint(),
        projectionContract.fingerprint(),
        *requiredDimensions.map { it.name }.sorted().toTypedArray(),
        *requiredNodeKinds.map { it.name }.sorted().toTypedArray(),
    )

    fun physicsFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-physics/v1",
        structuralFingerprint(),
        equation.physicsFingerprint(),
    )

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack/v1",
        version,
        equation.fingerprint(),
        structuralFingerprint(),
        projectionContract.fingerprint(),
    )

    fun changeKindComparedWith(
        baseline: WorldEquationPack,
    ): WorldEquationPackChangeKind =
        if (structuralFingerprint() == baseline.structuralFingerprint()) {
            WorldEquationPackChangeKind.PARAMETER_ONLY
        } else {
            WorldEquationPackChangeKind.STRUCTURAL
        }
}

data class WorldEquationPackCandidate private constructor(
    val baselinePackFingerprint: String,
    val candidate: WorldEquationPack,
    val changeKind: WorldEquationPackChangeKind,
    val id: String,
) {
    init {
        require(baselinePackFingerprint.isNotBlank())
        require(candidate.fingerprint() != baselinePackFingerprint)
        require(id == expectedId())
    }

    val directActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    private fun expectedId(): String = "world-equation-pack-candidate:" +
        StableFieldIds.fingerprint(
            "world-equation-pack-candidate/v1",
            baselinePackFingerprint,
            candidate.fingerprint(),
            changeKind.name,
        )

    companion object {
        fun create(
            baseline: WorldEquationPack,
            candidate: WorldEquationPack,
        ): WorldEquationPackCandidate {
            require(candidate.version != baseline.version) {
                "WorldEquationPack candidate requires a new pack version"
            }
            require(candidate.fingerprint() != baseline.fingerprint()) {
                "WorldEquationPack candidate must change the frozen pack artifact"
            }
            val kind = candidate.changeKindComparedWith(baseline)
            return WorldEquationPackCandidate(
                baselinePackFingerprint = baseline.fingerprint(),
                candidate = candidate,
                changeKind = kind,
                id = "world-equation-pack-candidate:" + StableFieldIds.fingerprint(
                    "world-equation-pack-candidate/v1",
                    baseline.fingerprint(),
                    candidate.fingerprint(),
                    kind.name,
                ),
            )
        }
    }
}
