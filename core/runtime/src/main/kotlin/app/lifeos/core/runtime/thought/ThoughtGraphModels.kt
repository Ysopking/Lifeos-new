package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import java.time.Instant

@JvmInline
value class ThoughtGraphNodeId(val value: String) {
    init { require(value.isNotBlank()) { "Thought graph node id must not be blank" } }
}

@JvmInline
value class ThoughtGraphEdgeId(val value: String) {
    init { require(value.isNotBlank()) { "Thought graph edge id must not be blank" } }
}

@JvmInline
value class ThoughtGraphDeltaId(val value: String) {
    init { require(value.isNotBlank()) { "Thought graph delta id must not be blank" } }
}

enum class ThoughtGraphNodeKind {
    PHOTON,
    EVIDENCE,
    HYPOTHESIS,
    GOAL,
    CONFLICT,
}

enum class ThoughtGraphEdgeKind {
    DERIVED_FROM,
    SUPPORTS,
    CONTRADICTS,
    REFERENCES,
    TRANSFORMS,
    EVIDENCE_FOR,
    EVIDENCE_AGAINST,
    COMPETES_WITH,
    CONFLICTS_WITH,
    TARGETS_GOAL,
    TEMPORALLY_SUPERSEDES,
}

enum class ThoughtGraphSourceKind {
    PHOTON,
    EVIDENCE,
    HYPOTHESIS,
    GOAL,
    SYSTEM,
}

enum class ThoughtGraphConflictSubjectKind {
    NODE,
    EDGE,
}

data class ThoughtGraphProvenance(
    val sourceKind: ThoughtGraphSourceKind,
    val sourceId: String,
    val sourceRevision: Long,
    val sourceFingerprint: String,
    val origin: String,
    val actor: String,
    val createdAt: Instant,
) {
    init {
        require(sourceId.isNotBlank()) { "Thought graph provenance source id must not be blank" }
        require(sourceRevision > 0L) { "Thought graph provenance revision must be positive" }
        require(sourceFingerprint.isNotBlank()) { "Thought graph source fingerprint must not be blank" }
        require(origin.isNotBlank()) { "Thought graph provenance origin must not be blank" }
        require(actor.isNotBlank()) { "Thought graph provenance actor must not be blank" }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "thought-graph/provenance/v1",
        sourceKind.name,
        sourceId,
        sourceRevision.toString(),
        sourceFingerprint,
        origin,
        actor,
        createdAt.toString(),
    )
}

data class ThoughtGraphNodeVersion(
    val id: ThoughtGraphNodeId,
    val kind: ThoughtGraphNodeKind,
    val semanticKey: String,
    val summary: String,
    val confidence: Double,
    val authority: Double,
    val validity: TemporalValidity,
    val provenance: ThoughtGraphProvenance,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(semanticKey.isNotBlank()) { "Thought graph node semantic key must not be blank" }
        require(summary.isNotBlank()) { "Thought graph node summary must not be blank" }
        require(confidence in 0.0..1.0) { "Thought graph node confidence must be in 0..1" }
        require(authority in 0.0..1.0) { "Thought graph node authority must be in 0..1" }
        require(attributes.keys.none { it.isBlank() }) { "Thought graph node attribute keys must not be blank" }
        require(
            id == createId(kind, provenance.sourceId, semanticKey)
        ) { "Thought graph node id must match kind/source/semantic identity" }
    }

    val sourceRevision: Long
        get() = provenance.sourceRevision

    val fingerprint: String = StableFieldIds.fingerprint(
        "thought-graph/node-version/v1",
        id.value,
        kind.name,
        semanticKey,
        summary,
        confidence.toString(),
        authority.toString(),
        validity.validFrom?.toString().orEmpty(),
        validity.validUntilExclusive?.toString().orEmpty(),
        provenance.fingerprint,
        *attributes.toSortedMap().flatMap { (key, value) ->
            listOf("attribute:$key", value)
        }.toTypedArray(),
    )

    companion object {
        fun createId(
            kind: ThoughtGraphNodeKind,
            sourceId: String,
            semanticKey: String,
        ): ThoughtGraphNodeId {
            require(sourceId.isNotBlank()) { "Thought graph node source id must not be blank" }
            require(semanticKey.isNotBlank()) { "Thought graph node semantic key must not be blank" }
            return ThoughtGraphNodeId(
                "thought-graph-node:" + StableFieldIds.fingerprint(
                    kind.name,
                    sourceId,
                    semanticKey,
                )
            )
        }

        fun create(
            kind: ThoughtGraphNodeKind,
            semanticKey: String,
            summary: String,
            confidence: Double,
            authority: Double,
            validity: TemporalValidity,
            provenance: ThoughtGraphProvenance,
            attributes: Map<String, String> = emptyMap(),
        ): ThoughtGraphNodeVersion = ThoughtGraphNodeVersion(
            id = createId(kind, provenance.sourceId, semanticKey),
            kind = kind,
            semanticKey = semanticKey,
            summary = summary,
            confidence = confidence,
            authority = authority,
            validity = validity,
            provenance = provenance,
            attributes = attributes.toSortedMap(),
        )
    }
}

data class ThoughtGraphEdgeVersion(
    val id: ThoughtGraphEdgeId,
    val sourceNodeId: ThoughtGraphNodeId,
    val targetNodeId: ThoughtGraphNodeId,
    val kind: ThoughtGraphEdgeKind,
    val semanticKey: String,
    val confidence: Double,
    val authority: Double,
    val validity: TemporalValidity,
    val provenance: ThoughtGraphProvenance,
    val explanation: String,
) {
    init {
        require(semanticKey.isNotBlank()) { "Thought graph edge semantic key must not be blank" }
        require(confidence in 0.0..1.0) { "Thought graph edge confidence must be in 0..1" }
        require(authority in 0.0..1.0) { "Thought graph edge authority must be in 0..1" }
        require(explanation.isNotBlank()) { "Thought graph edge explanation must not be blank" }
        require(
            id == createId(sourceNodeId, targetNodeId, kind, semanticKey)
        ) { "Thought graph edge id must match endpoints/kind/semantic identity" }
    }

    val sourceRevision: Long
        get() = provenance.sourceRevision

    val fingerprint: String = StableFieldIds.fingerprint(
        "thought-graph/edge-version/v1",
        id.value,
        sourceNodeId.value,
        targetNodeId.value,
        kind.name,
        semanticKey,
        confidence.toString(),
        authority.toString(),
        validity.validFrom?.toString().orEmpty(),
        validity.validUntilExclusive?.toString().orEmpty(),
        provenance.fingerprint,
        explanation,
    )

    companion object {
        fun createId(
            sourceNodeId: ThoughtGraphNodeId,
            targetNodeId: ThoughtGraphNodeId,
            kind: ThoughtGraphEdgeKind,
            semanticKey: String,
        ): ThoughtGraphEdgeId {
            require(semanticKey.isNotBlank()) { "Thought graph edge semantic key must not be blank" }
            return ThoughtGraphEdgeId(
                "thought-graph-edge:" + StableFieldIds.fingerprint(
                    sourceNodeId.value,
                    targetNodeId.value,
                    kind.name,
                    semanticKey,
                )
            )
        }

        fun create(
            sourceNodeId: ThoughtGraphNodeId,
            targetNodeId: ThoughtGraphNodeId,
            kind: ThoughtGraphEdgeKind,
            semanticKey: String,
            confidence: Double,
            authority: Double,
            validity: TemporalValidity,
            provenance: ThoughtGraphProvenance,
            explanation: String,
        ): ThoughtGraphEdgeVersion = ThoughtGraphEdgeVersion(
            id = createId(sourceNodeId, targetNodeId, kind, semanticKey),
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            kind = kind,
            semanticKey = semanticKey,
            confidence = confidence,
            authority = authority,
            validity = validity,
            provenance = provenance,
            explanation = explanation,
        )
    }
}

data class ThoughtGraphConflict(
    val id: String,
    val subjectKind: ThoughtGraphConflictSubjectKind,
    val subjectId: String,
    val sourceRevision: Long,
    val variantFingerprints: List<String>,
) {
    init {
        require(id.isNotBlank()) { "Thought graph conflict id must not be blank" }
        require(subjectId.isNotBlank()) { "Thought graph conflict subject id must not be blank" }
        require(sourceRevision > 0L) { "Thought graph conflict revision must be positive" }
        require(variantFingerprints.size >= 2) { "Thought graph conflict requires multiple variants" }
        require(variantFingerprints == variantFingerprints.distinct().sorted()) {
            "Thought graph conflict variants must be unique and deterministically ordered"
        }
    }

    companion object {
        fun create(
            subjectKind: ThoughtGraphConflictSubjectKind,
            subjectId: String,
            sourceRevision: Long,
            variantFingerprints: Iterable<String>,
        ): ThoughtGraphConflict {
            val variants = variantFingerprints.distinct().sorted()
            require(variants.size >= 2) { "Thought graph conflict requires multiple variants" }
            return ThoughtGraphConflict(
                id = "thought-graph-conflict:" + StableFieldIds.fingerprint(
                    subjectKind.name,
                    subjectId,
                    sourceRevision.toString(),
                    *variants.toTypedArray(),
                ),
                subjectKind = subjectKind,
                subjectId = subjectId,
                sourceRevision = sourceRevision,
                variantFingerprints = variants,
            )
        }
    }
}

data class ThoughtGraphDelta(
    val id: ThoughtGraphDeltaId,
    val sourceKey: String,
    val sourceRevision: Long,
    val nodeVersions: List<ThoughtGraphNodeVersion>,
    val edgeVersions: List<ThoughtGraphEdgeVersion>,
    val observedAt: Instant,
) {
    init {
        require(sourceKey.isNotBlank()) { "Thought graph delta source key must not be blank" }
        require(sourceRevision > 0L) { "Thought graph delta source revision must be positive" }
        require(nodeVersions == nodeVersions.sortedWith(nodeVersionOrdering())) {
            "Thought graph delta nodes must be deterministically ordered"
        }
        require(edgeVersions == edgeVersions.sortedWith(edgeVersionOrdering())) {
            "Thought graph delta edges must be deterministically ordered"
        }
        require(nodeVersions.isNotEmpty() || edgeVersions.isNotEmpty()) {
            "Thought graph delta must contain at least one graph version"
        }
        require(id == createId(sourceKey, sourceRevision, nodeVersions, edgeVersions)) {
            "Thought graph delta id must match its exact content"
        }
    }

    companion object {
        fun create(
            sourceKey: String,
            sourceRevision: Long,
            nodeVersions: Iterable<ThoughtGraphNodeVersion> = emptyList(),
            edgeVersions: Iterable<ThoughtGraphEdgeVersion> = emptyList(),
            observedAt: Instant,
        ): ThoughtGraphDelta {
            val nodes = nodeVersions.distinctBy { it.fingerprint }.sortedWith(nodeVersionOrdering())
            val edges = edgeVersions.distinctBy { it.fingerprint }.sortedWith(edgeVersionOrdering())
            return ThoughtGraphDelta(
                id = createId(sourceKey, sourceRevision, nodes, edges),
                sourceKey = sourceKey,
                sourceRevision = sourceRevision,
                nodeVersions = nodes,
                edgeVersions = edges,
                observedAt = observedAt,
            )
        }

        private fun createId(
            sourceKey: String,
            sourceRevision: Long,
            nodes: List<ThoughtGraphNodeVersion>,
            edges: List<ThoughtGraphEdgeVersion>,
        ): ThoughtGraphDeltaId = ThoughtGraphDeltaId(
            "thought-graph-delta:" + StableFieldIds.fingerprint(
                "thought-graph/delta/v1",
                sourceKey,
                sourceRevision.toString(),
                *buildList {
                    nodes.forEach { add("node:${it.fingerprint}") }
                    edges.forEach { add("edge:${it.fingerprint}") }
                }.toTypedArray(),
            )
        )
    }
}

data class ThoughtGraphState(
    val revision: Long = 0L,
    val nodeVersions: List<ThoughtGraphNodeVersion> = emptyList(),
    val edgeVersions: List<ThoughtGraphEdgeVersion> = emptyList(),
    val appliedDeltaIds: List<ThoughtGraphDeltaId> = emptyList(),
) {
    init {
        require(revision >= 0L) { "Thought graph revision must not be negative" }
        require(nodeVersions == nodeVersions.sortedWith(nodeVersionOrdering())) {
            "Thought graph node history must be deterministically ordered"
        }
        require(edgeVersions == edgeVersions.sortedWith(edgeVersionOrdering())) {
            "Thought graph edge history must be deterministically ordered"
        }
        require(nodeVersions.map { it.fingerprint }.distinct().size == nodeVersions.size) {
            "Thought graph node history cannot duplicate exact versions"
        }
        require(edgeVersions.map { it.fingerprint }.distinct().size == edgeVersions.size) {
            "Thought graph edge history cannot duplicate exact versions"
        }
        require(appliedDeltaIds == appliedDeltaIds.distinct().sortedBy { it.value }) {
            "Applied thought graph delta ids must be unique and deterministically ordered"
        }
        require(revision == appliedDeltaIds.size.toLong()) {
            "Thought graph revision must equal the number of unique applied deltas"
        }
    }
}

data class ThoughtGraphSnapshot(
    val revision: Long,
    val activeNodes: List<ThoughtGraphNodeVersion>,
    val activeEdges: List<ThoughtGraphEdgeVersion>,
    val conflicts: List<ThoughtGraphConflict>,
    val nodeHistoryCount: Int,
    val edgeHistoryCount: Int,
    val appliedDeltaIds: List<ThoughtGraphDeltaId>,
    val capturedAt: Instant,
    val historyFingerprint: String,
) {
    init {
        require(revision >= 0L) { "Thought graph snapshot revision must not be negative" }
        require(activeNodes == activeNodes.sortedBy { it.id.value }) {
            "Active thought graph nodes must be deterministically ordered"
        }
        require(activeEdges == activeEdges.sortedBy { it.id.value }) {
            "Active thought graph edges must be deterministically ordered"
        }
        require(conflicts == conflicts.sortedWith(conflictOrdering())) {
            "Thought graph conflicts must be deterministically ordered"
        }
        require(activeNodes.map { it.id }.distinct().size == activeNodes.size) {
            "Thought graph snapshot cannot contain duplicate active node ids"
        }
        require(activeEdges.map { it.id }.distinct().size == activeEdges.size) {
            "Thought graph snapshot cannot contain duplicate active edge ids"
        }
        require(nodeHistoryCount >= activeNodes.size) { "Node history cannot be smaller than active nodes" }
        require(edgeHistoryCount >= activeEdges.size) { "Edge history cannot be smaller than active edges" }
        require(appliedDeltaIds == appliedDeltaIds.distinct().sortedBy { it.value }) {
            "Snapshot delta ids must be unique and deterministically ordered"
        }
        require(historyFingerprint.isNotBlank()) { "Thought graph history fingerprint must not be blank" }
    }

    val contentFingerprint: String = StableFieldIds.fingerprint(
        "thought-graph/snapshot/v1",
        revision.toString(),
        historyFingerprint,
        *buildList {
            activeNodes.forEach { add("active-node:${it.fingerprint}") }
            activeEdges.forEach { add("active-edge:${it.fingerprint}") }
            conflicts.forEach { add("conflict:${it.id}") }
            appliedDeltaIds.forEach { add("delta:${it.value}") }
        }.toTypedArray(),
    )

    val snapshotId: String = "thought-graph-snapshot:$contentFingerprint"
}

internal fun nodeVersionOrdering(): Comparator<ThoughtGraphNodeVersion> =
    compareBy<ThoughtGraphNodeVersion> { it.id.value }
        .thenBy { it.sourceRevision }
        .thenBy { it.fingerprint }

internal fun edgeVersionOrdering(): Comparator<ThoughtGraphEdgeVersion> =
    compareBy<ThoughtGraphEdgeVersion> { it.id.value }
        .thenBy { it.sourceRevision }
        .thenBy { it.fingerprint }

internal fun conflictOrdering(): Comparator<ThoughtGraphConflict> =
    compareBy<ThoughtGraphConflict> { it.subjectKind.name }
        .thenBy { it.subjectId }
        .thenBy { it.sourceRevision }
        .thenBy { it.id }
