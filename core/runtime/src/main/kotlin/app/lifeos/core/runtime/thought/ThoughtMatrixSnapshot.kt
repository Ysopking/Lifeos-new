package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.time.Instant

data class ThoughtProjectionConflict(
    val nodeId: ThoughtNodeId,
    val photonId: PhotonId,
    val sourceRevision: Long,
    val fingerprints: List<String>,
) {
    init {
        require(sourceRevision > 0) { "Thought conflict source revision must be positive" }
        require(fingerprints.size >= 2) { "Thought conflict must contain at least two fingerprints" }
        require(fingerprints == fingerprints.distinct().sorted()) {
            "Thought conflict fingerprints must be unique and deterministically ordered"
        }
    }
}

data class ThoughtMatrixSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long,
    val nodes: List<ThoughtNode>,
    val relations: List<ThoughtRelation>,
    val conflicts: List<ThoughtProjectionConflict>,
    val capturedAt: Instant,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported thought matrix schema: $schemaVersion"
        }
        require(revision >= 0L) { "Thought matrix revision must not be negative" }
        require(nodes == nodes.sortedWith(nodeOrdering())) {
            "Thought nodes must be deterministically ordered"
        }
        require(nodes.map { it.id }.distinct().size == nodes.size) {
            "Thought matrix cannot contain duplicate thought node ids"
        }
        require(relations == relations.sortedBy { it.id }) {
            "Thought relations must be deterministically ordered"
        }
        require(relations.map { it.id }.distinct().size == relations.size) {
            "Thought relations must have unique ids"
        }
        require(conflicts == conflicts.sortedWith(conflictOrdering())) {
            "Thought conflicts must be deterministically ordered"
        }
        require(conflicts.map { it.nodeId }.distinct().size == conflicts.size) {
            "Thought matrix cannot contain duplicate conflict records"
        }
        val conflictedNodeIds = conflicts.mapTo(mutableSetOf()) { it.nodeId }
        require(nodes.none { it.id in conflictedNodeIds }) {
            "Conflicted thought nodes cannot remain active"
        }
        require(relations.none { it.sourceNodeId in conflictedNodeIds }) {
            "Conflicted thought nodes cannot retain active source relations"
        }
    }

    val totalEnergy: Double = nodes.sumOf { it.energy }
    val contentFingerprint: String = StableFieldIds.fingerprint(
        "thought-matrix/v2",
        *buildList {
            nodes.forEach { node ->
                add("node")
                add(node.id.value)
                add(node.photonId.value)
                add(node.sourceRevision.toString())
                add(node.provenance.sourceFingerprint)
                add(node.fieldDomainId.value)
                add(node.semanticKey)
                add(node.summary)
                add(node.semanticMass.toString())
                add(node.energy.toString())
                add(node.confidence.toString())
                add(node.validity.validFrom?.toString().orEmpty())
                add(node.validity.validUntilExclusive?.toString().orEmpty())
                add(node.lifecycle.name)
                add(node.verification.name)
                add(node.provenance.source)
                add(node.provenance.actor)
                add(node.provenance.createdAt.toString())
                node.tags.sorted().forEach { add("tag:$it") }
            }
            relations.forEach { relation ->
                add("relation")
                add(relation.id)
                add(relation.sourceNodeId.value)
                add(relation.sourcePhotonId.value)
                add(relation.targetPhotonId.value)
                add(relation.type.name)
                add(relation.weight.toString())
                add(relation.source.sourceRevision.toString())
                add(relation.source.origin)
            }
            conflicts.forEach { conflict ->
                add("conflict")
                add(conflict.nodeId.value)
                add(conflict.photonId.value)
                add(conflict.sourceRevision.toString())
                conflict.fingerprints.forEach { add(it) }
            }
        }.toTypedArray(),
    )
    val snapshotId: String = "thought-snapshot:$contentFingerprint"

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        fun empty(capturedAt: Instant): ThoughtMatrixSnapshot = ThoughtMatrixSnapshot(
            revision = 0L,
            nodes = emptyList(),
            relations = emptyList(),
            conflicts = emptyList(),
            capturedAt = capturedAt,
        )

        internal fun nodeOrdering(): Comparator<ThoughtNode> =
            compareBy<ThoughtNode> { it.id.value }
                .thenByDescending { it.sourceRevision }

        internal fun conflictOrdering(): Comparator<ThoughtProjectionConflict> =
            compareBy<ThoughtProjectionConflict> { it.nodeId.value }
                .thenBy { it.sourceRevision }
    }
}
