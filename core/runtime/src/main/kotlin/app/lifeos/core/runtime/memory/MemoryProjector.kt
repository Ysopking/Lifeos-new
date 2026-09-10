package app.lifeos.core.runtime.memory

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import app.lifeos.core.runtime.thought.ThoughtNode
import app.lifeos.core.runtime.thought.ThoughtNodeId
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus

data class MemoryProjectionDirective(
    val thoughtNodeId: ThoughtNodeId,
    val kind: MemoryKind,
    val semanticKind: SemanticMemoryKind? = null,
    val evidenceKind: MemoryEvidenceKind,
    val semanticKeyOverride: String? = null,
) {
    init {
        require(kind != MemoryKind.EPISODIC) {
            "Episodic memory is projected automatically and needs no directive"
        }
        if (kind == MemoryKind.SEMANTIC) {
            require(semanticKind != null) { "Semantic directive requires semantic subtype" }
        } else {
            require(semanticKind == null) { "Only semantic directives may have semantic subtype" }
        }
        require(semanticKeyOverride == null || semanticKeyOverride.isNotBlank()) {
            "Semantic key override must not be blank"
        }
    }
}

data class MemoryProjectionRejection(
    val thoughtNodeId: ThoughtNodeId,
    val kind: MemoryKind,
    val reason: String,
)

data class MemoryProjectionReport(
    val candidates: List<MemoryItem>,
    val rejected: List<MemoryProjectionRejection>,
    val sourceConflicts: List<MemorySourceConflict>,
) {
    init {
        require(candidates == candidates.sortedBy { it.id.value }) {
            "Memory candidates must be deterministically ordered"
        }
        require(rejected == rejected.sortedWith(
            compareBy<MemoryProjectionRejection> { it.thoughtNodeId.value }
                .thenBy { it.kind.name }
                .thenBy { it.reason }
        )) { "Memory projection rejections must be deterministically ordered" }
        require(sourceConflicts == sourceConflicts.sortedBy { it.id }) {
            "Memory source conflicts must be deterministically ordered"
        }
    }
}

/**
 * Converts the rebuildable ThoughtMatrix projection into typed memory candidates.
 *
 * Every active thought becomes episodic memory. Semantic/procedural memory requires an explicit
 * directive; preference memory additionally requires USER_CONFIRMED evidence. Repetition alone
 * therefore cannot create a preference.
 */
class MemoryProjector {
    fun project(
        snapshot: ThoughtMatrixSnapshot,
        directives: Iterable<MemoryProjectionDirective> = emptyList(),
    ): MemoryProjectionReport {
        val nodesById = snapshot.nodes.associateBy { it.id }
        val candidates = mutableListOf<MemoryItem>()
        val rejected = mutableListOf<MemoryProjectionRejection>()

        snapshot.nodes.forEach { node ->
            candidates += node.toMemoryItem(
                kind = MemoryKind.EPISODIC,
                semanticKind = null,
                evidenceKind = MemoryEvidenceKind.OBSERVATION,
                semanticKey = node.semanticKey,
            )
        }

        directives
            .distinct()
            .sortedWith(
                compareBy<MemoryProjectionDirective> { it.thoughtNodeId.value }
                    .thenBy { it.kind.name }
                    .thenBy { it.semanticKind?.name.orEmpty() }
                    .thenBy { it.evidenceKind.name }
                    .thenBy { it.semanticKeyOverride.orEmpty() }
            )
            .forEach { directive ->
                val node = nodesById[directive.thoughtNodeId]
                if (node == null) {
                    rejected += MemoryProjectionRejection(
                        thoughtNodeId = directive.thoughtNodeId,
                        kind = directive.kind,
                        reason = "thought-node-not-active-or-conflicted",
                    )
                    return@forEach
                }
                if (
                    directive.semanticKind == SemanticMemoryKind.PREFERENCE &&
                    directive.evidenceKind != MemoryEvidenceKind.USER_CONFIRMED
                ) {
                    rejected += MemoryProjectionRejection(
                        thoughtNodeId = directive.thoughtNodeId,
                        kind = directive.kind,
                        reason = "preference-requires-user-confirmed-evidence",
                    )
                    return@forEach
                }

                candidates += node.toMemoryItem(
                    kind = directive.kind,
                    semanticKind = directive.semanticKind,
                    evidenceKind = directive.evidenceKind,
                    semanticKey = directive.semanticKeyOverride ?: node.semanticKey,
                )
            }

        val sourceConflicts = snapshot.conflicts.map { conflict ->
            MemorySourceConflict(
                id = "memory-source-conflict:" + StableFieldIds.fingerprint(
                    conflict.nodeId.value,
                    conflict.photonId.value,
                    conflict.sourceRevision.toString(),
                    *conflict.fingerprints.toTypedArray(),
                ),
                thoughtNodeId = conflict.nodeId,
                photonId = conflict.photonId,
                sourceRevision = conflict.sourceRevision,
                fingerprints = conflict.fingerprints,
            )
        }.sortedBy { it.id }

        return MemoryProjectionReport(
            candidates = candidates.distinctBy { it.id }.sortedBy { it.id.value },
            rejected = rejected.sortedWith(
                compareBy<MemoryProjectionRejection> { it.thoughtNodeId.value }
                    .thenBy { it.kind.name }
                    .thenBy { it.reason }
            ),
            sourceConflicts = sourceConflicts,
        )
    }

    private fun ThoughtNode.toMemoryItem(
        kind: MemoryKind,
        semanticKind: SemanticMemoryKind?,
        evidenceKind: MemoryEvidenceKind,
        semanticKey: String,
    ): MemoryItem {
        val evidence = MemoryEvidenceRef(
            thoughtNodeId = id,
            photonId = photonId,
            sourceRevision = sourceRevision,
            sourceFingerprint = provenance.sourceFingerprint,
            kind = evidenceKind,
            source = provenance.source,
            actor = provenance.actor,
            observedAt = provenance.createdAt,
        )
        return MemoryItem.create(
            key = memoryKey(this, kind, semanticKey),
            kind = kind,
            semanticKind = semanticKind,
            semanticKey = semanticKey,
            content = summary,
            confidence = confidence,
            verification = verification.toMemoryVerification(evidenceKind),
            evidence = listOf(evidence),
            observationCount = 1,
            tags = tags,
        )
    }

    private fun memoryKey(
        node: ThoughtNode,
        kind: MemoryKind,
        semanticKey: String,
    ): MemoryKey {
        val parts = mutableListOf(
            "memory-key/v1",
            kind.name,
            node.fieldDomainId.value,
            semanticKey,
        )
        if (kind == MemoryKind.EPISODIC) {
            parts += node.id.value
        }
        return MemoryKey("memory-key:" + StableFieldIds.fingerprint(*parts.toTypedArray()))
    }

    private fun ThoughtVerificationStatus.toMemoryVerification(
        evidenceKind: MemoryEvidenceKind,
    ): MemoryVerificationStatus = when {
        evidenceKind == MemoryEvidenceKind.USER_CONFIRMED ||
            evidenceKind == MemoryEvidenceKind.VERIFIED_OUTCOME -> MemoryVerificationStatus.VERIFIED
        this == ThoughtVerificationStatus.VERIFIED -> MemoryVerificationStatus.VERIFIED
        this == ThoughtVerificationStatus.OBSERVED -> MemoryVerificationStatus.OBSERVED
        this == ThoughtVerificationStatus.CONFLICTED -> MemoryVerificationStatus.CONFLICTED
        else -> MemoryVerificationStatus.UNVERIFIED
    }
}
