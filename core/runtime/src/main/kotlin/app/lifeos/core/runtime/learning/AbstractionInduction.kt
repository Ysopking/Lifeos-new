package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.WorldFormulaSnapshot

enum class AbstractionInductionKind {
    CONCEPT,
    SCHEMA,
    RELATION,
}

data class AbstractionPatternEvidence(
    val nodeIds: Set<String>,
    val edgeKinds: Set<ThoughtGraphEdgeKind>,
    val patternFingerprint: String,
) {
    init {
        require(nodeIds.size >= 2) {
            "Abstraction induction requires a multi-node ThoughtGraph pattern"
        }
        require(nodeIds.none { it.isBlank() })
        require(edgeKinds.isNotEmpty())
        require(patternFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "abstraction-pattern-evidence/v1",
        patternFingerprint,
        *nodeIds.sorted().map { "node:$it" }.toTypedArray(),
        *edgeKinds.map { it.name }.sorted().map { "edge:$it" }.toTypedArray(),
    )

    companion object {
        fun fromWorkingSet(
            workingSet: ThoughtGraphWorkingSet,
            nodeIds: Set<String>,
        ): AbstractionPatternEvidence {
            require(nodeIds.size >= 2)
            val selected = workingSet.nodes.filter { it.id.value in nodeIds }
            require(selected.size == nodeIds.size) {
                "Abstraction pattern references nodes outside the frozen ThoughtGraph working set"
            }
            val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
            val edges = workingSet.edges.filter {
                it.sourceNodeId in selectedIds && it.targetNodeId in selectedIds
            }
            require(edges.isNotEmpty()) {
                "Abstraction pattern must contain at least one structural relation"
            }
            val fingerprint = StableFieldIds.fingerprint(
                "abstraction-pattern-from-working-set/v1",
                workingSet.fingerprint,
                *selected.sortedBy { it.id.value }.map { it.fingerprint }.toTypedArray(),
                *edges.sortedBy { it.id.value }.map { it.fingerprint }.toTypedArray(),
            )
            return AbstractionPatternEvidence(
                nodeIds = nodeIds.toSortedSet(),
                edgeKinds = edges.mapTo(sortedSetOf(compareBy { it.name })) { it.kind },
                patternFingerprint = fingerprint,
            )
        }
    }
}

data class AbstractionCandidate private constructor(
    val id: String,
    val kind: AbstractionInductionKind,
    val semanticKey: String,
    val summary: String,
    val producerId: String,
    val thoughtWorkingSetFingerprint: String,
    val pattern: AbstractionPatternEvidence,
    val worldSnapshotId: String,
    val worldSnapshotFingerprint: String,
    val worldEquationVersion: String,
    val affectedTargets: Set<WorldTargetRef>,
    val affectedDimensions: Set<WorldSignalDimension>,
    val confidence: Double,
) {
    init {
        require(id.isNotBlank())
        require(semanticKey.isNotBlank())
        require(summary.isNotBlank())
        require(producerId.isNotBlank())
        require(thoughtWorkingSetFingerprint.isNotBlank())
        require(worldSnapshotId.isNotBlank())
        require(worldSnapshotFingerprint.isNotBlank())
        require(worldEquationVersion.isNotBlank())
        require(affectedTargets.isNotEmpty())
        require(affectedDimensions.isNotEmpty())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(id == expectedId()) {
            "Abstraction candidate id does not match content"
        }
    }

    val directSchemaMutationAllowed: Boolean
        get() = false

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "abstraction-candidate/v1",
        kind.name,
        semanticKey,
        summary,
        producerId,
        thoughtWorkingSetFingerprint,
        pattern.fingerprint(),
        worldSnapshotId,
        worldSnapshotFingerprint,
        worldEquationVersion,
        java.lang.Double.toHexString(confidence),
        *affectedTargets
            .sortedWith(compareBy({ it.kind.name }, { it.key }))
            .map { "target:${it.fingerprint()}" }
            .toTypedArray(),
        *affectedDimensions.map { it.name }.sorted().map { "dimension:$it" }.toTypedArray(),
    )

    private fun expectedId(): String = "abstraction-candidate:${fingerprint()}"

    companion object {
        fun create(
            kind: AbstractionInductionKind,
            semanticKey: String,
            summary: String,
            producerId: String,
            workingSet: ThoughtGraphWorkingSet,
            pattern: AbstractionPatternEvidence,
            worldSnapshot: WorldFormulaSnapshot,
            affectedTargets: Set<WorldTargetRef>,
            affectedDimensions: Set<WorldSignalDimension>,
            confidence: Double,
        ): AbstractionCandidate {
            require(pattern.nodeIds.all { id ->
                workingSet.nodes.any { it.id.value == id }
            }) {
                "Abstraction candidate pattern changed ThoughtGraph lineage"
            }
            require(affectedTargets.isNotEmpty() && affectedTargets.none { it.key.isBlank() }) {
                "Abstraction candidate requires explicit affected World targets"
            }
            val snapshotDimensions = worldSnapshot.finalState.vectors.values
                .flatMapTo(linkedSetOf()) { it.dimensions() }
            require(affectedDimensions.isNotEmpty()) {
                "Abstraction candidate requires affected World dimensions"
            }
            require(affectedDimensions.all(snapshotDimensions::contains)) {
                "Abstraction candidate references dimensions absent from the bound World snapshot"
            }

            val fingerprint = StableFieldIds.fingerprint(
                "abstraction-candidate/v1",
                kind.name,
                semanticKey,
                summary,
                producerId,
                workingSet.fingerprint,
                pattern.fingerprint(),
                worldSnapshot.id,
                worldSnapshot.contentFingerprint(),
                worldSnapshot.equationVersion,
                java.lang.Double.toHexString(confidence),
                *affectedTargets
                    .sortedWith(compareBy({ it.kind.name }, { it.key }))
                    .map { "target:${it.fingerprint()}" }
                    .toTypedArray(),
                *affectedDimensions.map { it.name }.sorted().map { "dimension:$it" }.toTypedArray(),
            )
            return AbstractionCandidate(
                id = "abstraction-candidate:$fingerprint",
                kind = kind,
                semanticKey = semanticKey,
                summary = summary,
                producerId = producerId,
                thoughtWorkingSetFingerprint = workingSet.fingerprint,
                pattern = pattern,
                worldSnapshotId = worldSnapshot.id,
                worldSnapshotFingerprint = worldSnapshot.contentFingerprint(),
                worldEquationVersion = worldSnapshot.equationVersion,
                affectedTargets = affectedTargets,
                affectedDimensions = affectedDimensions,
                confidence = confidence,
            )
        }
    }
}

data class AbstractionValidationEvidence(
    val candidateId: String,
    val producerId: String,
    val validatorId: String,
    val passed: Boolean,
    val evidenceFingerprint: String,
) {
    init {
        require(candidateId.isNotBlank())
        require(producerId.isNotBlank())
        require(validatorId.isNotBlank())
        require(producerId != validatorId) {
            "Abstraction validation must be independent from induction producer"
        }
        require(evidenceFingerprint.isNotBlank())
    }
}

data class ValidatedAbstraction private constructor(
    val candidate: AbstractionCandidate,
    val validation: AbstractionValidationEvidence,
) {
    init {
        require(validation.candidateId == candidate.id)
        require(validation.producerId == candidate.producerId)
        require(validation.passed) {
            "Abstraction validation must pass before World projection"
        }
    }

    val directSchemaMutationAllowed: Boolean
        get() = false

    val directWorldStateMutationAllowed: Boolean
        get() = false

    companion object {
        fun create(
            candidate: AbstractionCandidate,
            validation: AbstractionValidationEvidence,
        ): ValidatedAbstraction = ValidatedAbstraction(candidate, validation)
    }
}

data class AbstractionWorldProjectionCandidate(
    val id: String,
    val abstractionId: String,
    val semanticKey: String,
    val worldSnapshotId: String,
    val worldSnapshotFingerprint: String,
    val worldEquationVersion: String,
    val affectedTargets: Set<WorldTargetRef>,
    val affectedDimensions: Set<WorldSignalDimension>,
    val sourceValidationFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(abstractionId.isNotBlank())
        require(semanticKey.isNotBlank())
        require(worldSnapshotId.isNotBlank())
        require(worldSnapshotFingerprint.isNotBlank())
        require(worldEquationVersion.isNotBlank())
        require(affectedTargets.isNotEmpty())
        require(affectedDimensions.isNotEmpty())
        require(sourceValidationFingerprint.isNotBlank())
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false

    companion object {
        fun from(validated: ValidatedAbstraction): AbstractionWorldProjectionCandidate {
            val candidate = validated.candidate
            val validation = validated.validation
            val id = "abstraction-world-projection:${StableFieldIds.fingerprint(
                "abstraction-world-projection/v1",
                candidate.id,
                validation.evidenceFingerprint,
                candidate.worldSnapshotId,
                candidate.worldSnapshotFingerprint,
                candidate.worldEquationVersion,
            )}"
            return AbstractionWorldProjectionCandidate(
                id = id,
                abstractionId = candidate.id,
                semanticKey = candidate.semanticKey,
                worldSnapshotId = candidate.worldSnapshotId,
                worldSnapshotFingerprint = candidate.worldSnapshotFingerprint,
                worldEquationVersion = candidate.worldEquationVersion,
                affectedTargets = candidate.affectedTargets,
                affectedDimensions = candidate.affectedDimensions,
                sourceValidationFingerprint = validation.evidenceFingerprint,
            )
        }
    }
}

/**
 * B165 pattern induction helper for the existing ContinuousLearningCoordinator.
 *
 * It only requests durable review/induction work. It never mutates ThoughtGraph schema, WorldState
 * or the productive WorldFormula head directly.
 */
class AbstractionLearningUpdater : LearningAbstractionUpdater {
    override suspend fun update(event: LearningEvent): LearningProjectionResult {
        val pattern = event.attributes["abstractionPatternFingerprint"]
            ?: return LearningProjectionResult()
        val kind = event.attributes["abstractionKind"]
            ?.let { runCatching { AbstractionInductionKind.valueOf(it) }.getOrNull() }
            ?: AbstractionInductionKind.CONCEPT

        val requests = buildList {
            add(
                LearningWorkRequest(
                    kind = LearningDerivedWorkKind.ABSTRACTION_REVIEW,
                    reason = "abstraction-pattern:$pattern",
                )
            )
            when (kind) {
                AbstractionInductionKind.CONCEPT -> Unit
                AbstractionInductionKind.SCHEMA -> add(
                    LearningWorkRequest(
                        kind = LearningDerivedWorkKind.SCHEMA_INDUCTION,
                        reason = "schema-candidate:$pattern",
                    )
                )
                AbstractionInductionKind.RELATION -> add(
                    LearningWorkRequest(
                        kind = LearningDerivedWorkKind.RELATION_INDUCTION,
                        reason = "relation-candidate:$pattern",
                    )
                )
            }
        }
        return LearningProjectionResult(
            changed = false,
            evidence = listOf("abstraction-pattern:$pattern"),
            workRequests = requests,
        )
    }
}
