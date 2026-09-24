package app.lifeos.core.runtime.world

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.extension.ExtensionPointKind
import app.lifeos.core.runtime.extension.ExtensionPointRegistration
import app.lifeos.core.runtime.extension.ExtensionPointSnapshot
import app.lifeos.core.runtime.extension.WorldModelProjectionProvider
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import java.time.Duration
import java.time.Instant

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
        val raw = delegate.project(context.inputs)
        require(raw.map { it.target }.distinct().size == raw.size) {
            "World projection provider emitted duplicate targets"
        }
        require(raw.all { it.target.kind in descriptor.nodeKinds }) {
            "World projection provider emitted undeclared node kind"
        }
        require(
            raw.flatMap { it.vector.dimensions() }
                .all { it in descriptor.signalDimensions }
        ) {
            "World projection provider emitted undeclared signal dimension"
        }
        val projected = raw.sortedWith(
            compareBy({ it.target.kind.name }, { it.target.key })
        )
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
        val descriptor = provider.descriptor
        require(projection.providerId == providerId)
        require(projection.contextFingerprint == context.fingerprint())
        require(projection.inputs.all { it.target.kind in descriptor.nodeKinds }) {
            "World projection provider emitted undeclared node kind"
        }
        require(
            projection.inputs
                .flatMap { it.vector.dimensions() }
                .all { it in descriptor.signalDimensions }
        ) {
            "World projection provider emitted undeclared signal dimension"
        }
        require(projection.relations.all { it.kind in descriptor.relationKinds }) {
            "World projection provider emitted undeclared relation kind"
        }
        return projection
    }
}

// ---- B455 State Contract + Sufficiency ----

@JvmInline
value class StateDimensionId(val value: String) {
    init {
        require(value.isNotBlank()) { "State dimension id must not be blank" }
    }

    override fun toString(): String = value
}

data class StateDimensionRequirement(
    val dimension: StateDimensionId,
    val minimumAuthority: ObservationAuthorityClass,
    val maximumAge: Duration? = null,
    val minimumEvidenceCount: Int = 1,
    val allowConflicts: Boolean = false,
) {
    init {
        require(minimumEvidenceCount > 0)
        require(maximumAge == null || (!maximumAge.isNegative && !maximumAge.isZero))
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "state-dimension-requirement/v1",
        dimension.value,
        minimumAuthority.name,
        maximumAge?.toString().orEmpty(),
        minimumEvidenceCount.toString(),
        allowConflicts.toString(),
    )
}

data class StateContract(
    val id: String,
    val domain: FieldDomainId,
    val dimensions: List<StateDimensionRequirement>,
) {
    init {
        require(id.isNotBlank())
        require(dimensions.isNotEmpty())
        require(dimensions.map { it.dimension }.distinct().size == dimensions.size)
        require(dimensions == dimensions.sortedBy { it.dimension.value }) {
            "State contract dimensions must be canonical"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "state-contract/v1",
        id,
        domain.value,
        *dimensions.map { it.fingerprint }.toTypedArray(),
    )

    companion object {
        fun create(
            id: String,
            domain: FieldDomainId,
            dimensions: Collection<StateDimensionRequirement>,
        ): StateContract = StateContract(
            id = id,
            domain = domain,
            dimensions = dimensions.sortedBy { it.dimension.value },
        )
    }
}

data class StateDimensionEvidence(
    val dimension: StateDimensionId,
    val evidenceIds: Set<String>,
    val strongestAuthority: ObservationAuthorityClass,
    val latestObservedAt: Instant,
    val conflictCount: Int = 0,
) {
    init {
        require(evidenceIds.none { it.isBlank() })
        require(conflictCount >= 0)
    }
}

enum class StateSufficiencyStatus {
    SUFFICIENT,
    INSUFFICIENT,
    STALE,
    CONFLICTED,
}

data class StateSufficiencyResult(
    val contractId: String,
    val contractFingerprint: String,
    val status: StateSufficiencyStatus,
    val satisfied: Set<StateDimensionId>,
    val missing: Set<StateDimensionId>,
    val stale: Set<StateDimensionId>,
    val conflicted: Set<StateDimensionId>,
    val supportingEvidenceIds: Set<String>,
) {
    init {
        require(contractId.isNotBlank())
        require(contractFingerprint.isNotBlank())
        require(
            listOf(satisfied, missing, stale, conflicted)
                .flatten()
                .groupingBy { it }
                .eachCount()
                .values
                .all { it == 1 }
        ) {
            "One state dimension cannot occupy multiple sufficiency result classes"
        }
        require(supportingEvidenceIds.none { it.isBlank() })
    }
}

/**
 * B455 deterministic state sufficiency detector.
 *
 * Sufficiency is evaluated against an explicit contract. Confidence alone cannot create a complete
 * state; missing dimensions, weak authority, stale evidence and conflicts remain explicit.
 */
class StateSufficiencyDetector {
    fun evaluate(
        contract: StateContract,
        evidence: Collection<StateDimensionEvidence>,
        at: Instant,
    ): StateSufficiencyResult {
        val byDimension = evidence
            .groupBy { it.dimension }
            .mapValues { (_, entries) ->
                require(entries.size == 1) {
                    "State dimension evidence must be pre-reconciled into one summary"
                }
                entries.single()
            }

        val satisfied = linkedSetOf<StateDimensionId>()
        val missing = linkedSetOf<StateDimensionId>()
        val stale = linkedSetOf<StateDimensionId>()
        val conflicted = linkedSetOf<StateDimensionId>()
        val supporting = linkedSetOf<String>()

        contract.dimensions.forEach { requirement ->
            val current = byDimension[requirement.dimension]
            if (
                current == null ||
                current.evidenceIds.size < requirement.minimumEvidenceCount ||
                current.strongestAuthority.rank < requirement.minimumAuthority.rank
            ) {
                missing += requirement.dimension
                return@forEach
            }

            supporting += current.evidenceIds

            if (current.conflictCount > 0 && !requirement.allowConflicts) {
                conflicted += requirement.dimension
                return@forEach
            }

            val maximumAge = requirement.maximumAge
            if (
                maximumAge != null &&
                current.latestObservedAt.plus(maximumAge).isBefore(at)
            ) {
                stale += requirement.dimension
                return@forEach
            }

            satisfied += requirement.dimension
        }

        val status = when {
            conflicted.isNotEmpty() -> StateSufficiencyStatus.CONFLICTED
            missing.isNotEmpty() -> StateSufficiencyStatus.INSUFFICIENT
            stale.isNotEmpty() -> StateSufficiencyStatus.STALE
            else -> StateSufficiencyStatus.SUFFICIENT
        }

        return StateSufficiencyResult(
            contractId = contract.id,
            contractFingerprint = contract.fingerprint,
            status = status,
            satisfied = satisfied,
            missing = missing,
            stale = stale,
            conflicted = conflicted,
            supportingEvidenceIds = supporting,
        )
    }
}

// ---- B456 Unified World Gaps ----

sealed interface WorldGap {
    val id: String
    val domain: FieldDomainId
    val severity: GapSeverity
    val reason: String

    data class Perception(
        override val domain: FieldDomainId,
        val missingDimensions: Set<StateDimensionId>,
        val staleDimensions: Set<StateDimensionId> = emptySet(),
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(missingDimensions.isNotEmpty() || staleDimensions.isNotEmpty())
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-perception/v1",
            domain.value,
            severity.name,
            reason,
            *missingDimensions.map { "missing:${it.value}" }.sorted().toTypedArray(),
            *staleDimensions.map { "stale:${it.value}" }.sorted().toTypedArray(),
        )
    }

    data class Capability(
        override val domain: FieldDomainId,
        val capabilityId: String,
        val providerCandidates: Set<String> = emptySet(),
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(capabilityId.isNotBlank())
            require(providerCandidates.none { it.isBlank() })
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-capability/v1",
            domain.value,
            capabilityId,
            severity.name,
            reason,
            *providerCandidates.sorted().toTypedArray(),
        )
    }

    data class Consistency(
        override val domain: FieldDomainId,
        val conflictingDimensions: Set<StateDimensionId>,
        val evidenceIds: Set<String>,
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(conflictingDimensions.isNotEmpty())
            require(evidenceIds.none { it.isBlank() })
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-consistency/v1",
            domain.value,
            severity.name,
            reason,
            *conflictingDimensions.map { it.value }.sorted().toTypedArray(),
            *evidenceIds.sorted().toTypedArray(),
        )
    }

    data class Verification(
        override val domain: FieldDomainId,
        val actionGraphId: String,
        val expectedStateContract: String,
        val missingObservationContract: String,
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(actionGraphId.isNotBlank())
            require(expectedStateContract.isNotBlank())
            require(missingObservationContract.isNotBlank())
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-verification/v1",
            domain.value,
            actionGraphId,
            expectedStateContract,
            missingObservationContract,
            severity.name,
            reason,
        )
    }
}

/**
 * B456 bridge from B455 StateSufficiency to explicit world gaps.
 *
 * Perception and consistency are intentionally separate: contradictory evidence is not represented
 * as merely "more information needed".
 */
object StateWorldGapDetector {
    fun detect(
        contract: StateContract,
        result: StateSufficiencyResult,
    ): List<WorldGap> {
        require(result.contractId == contract.id)
        require(result.contractFingerprint == contract.fingerprint)

        return buildList {
            if (result.missing.isNotEmpty() || result.stale.isNotEmpty()) {
                add(
                    WorldGap.Perception(
                        domain = contract.domain,
                        missingDimensions = result.missing,
                        staleDimensions = result.stale,
                        reason = when {
                            result.missing.isNotEmpty() && result.stale.isNotEmpty() ->
                                "state-dimensions-missing-and-stale"
                            result.missing.isNotEmpty() ->
                                "state-dimensions-missing"
                            else ->
                                "state-dimensions-stale"
                        },
                    )
                )
            }
            if (result.conflicted.isNotEmpty()) {
                add(
                    WorldGap.Consistency(
                        domain = contract.domain,
                        conflictingDimensions = result.conflicted,
                        evidenceIds = result.supportingEvidenceIds,
                        reason = "state-evidence-conflict",
                    )
                )
            }
        }.sortedBy { it.id }
    }
}

/** Adapts the existing capability gap model instead of replacing CapabilityRegistry semantics. */
object CapabilityWorldGapAdapter {
    fun adapt(
        domain: FieldDomainId,
        gap: CapabilityGap,
    ): WorldGap.Capability = WorldGap.Capability(
        domain = domain,
        capabilityId = gap.requirement.capabilityId.value,
        providerCandidates = gap.candidateProviderIds.toSet(),
        severity = gap.requirement.severity,
        reason = gap.type.name.lowercase(),
    )
}
