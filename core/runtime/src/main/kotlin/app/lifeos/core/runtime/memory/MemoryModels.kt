package app.lifeos.core.runtime.memory

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtNodeId
import java.time.Instant

@JvmInline
value class MemoryId(val value: String) {
    init { require(value.isNotBlank()) { "Memory id must not be blank" } }
}

@JvmInline
value class MemoryKey(val value: String) {
    init { require(value.isNotBlank()) { "Memory key must not be blank" } }
}

enum class MemoryKind {
    EPISODIC,
    SEMANTIC,
    PROCEDURAL,
}

enum class SemanticMemoryKind {
    FACT,
    PREFERENCE,
}

enum class MemoryEvidenceKind {
    OBSERVATION,
    INFERENCE,
    USER_CONFIRMED,
    GENERATED_STRATEGY,
    VERIFIED_OUTCOME,
}

enum class MemoryVerificationStatus {
    UNVERIFIED,
    OBSERVED,
    VERIFIED,
    CONFLICTED,
}

data class MemoryEvidenceRef(
    val thoughtNodeId: ThoughtNodeId,
    val photonId: PhotonId,
    val sourceRevision: Long,
    val sourceFingerprint: String,
    val kind: MemoryEvidenceKind,
    val source: String,
    val actor: String,
    val observedAt: Instant,
) {
    init {
        require(sourceRevision > 0) { "Memory evidence revision must be positive" }
        require(sourceFingerprint.isNotBlank()) { "Memory evidence fingerprint must not be blank" }
        require(source.isNotBlank()) { "Memory evidence source must not be blank" }
        require(actor.isNotBlank()) { "Memory evidence actor must not be blank" }
    }

    val stableKey: String
        get() = listOf(
            thoughtNodeId.value,
            photonId.value,
            sourceRevision.toString(),
            sourceFingerprint,
            kind.name,
        ).joinToString("|")
}

data class MemoryItem(
    val id: MemoryId,
    val key: MemoryKey,
    val kind: MemoryKind,
    val semanticKind: SemanticMemoryKind? = null,
    val semanticKey: String,
    val content: String,
    val normalizedClaim: String,
    val confidence: Double,
    val verification: MemoryVerificationStatus,
    val evidence: List<MemoryEvidenceRef>,
    val firstObservedAt: Instant,
    val lastObservedAt: Instant,
    val observationCount: Int,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(semanticKey.isNotBlank()) { "Memory semantic key must not be blank" }
        require(content.isNotBlank()) { "Memory content must not be blank" }
        require(normalizedClaim.isNotBlank()) { "Memory normalized claim must not be blank" }
        require(confidence in 0.0..1.0) { "Memory confidence must be in 0..1" }
        require(verification != MemoryVerificationStatus.CONFLICTED) {
            "Conflicted memory must be represented by MemoryConflict, not active MemoryItem"
        }
        require(evidence.isNotEmpty()) { "Every memory item must link to source evidence" }
        require(evidence == evidence.distinctBy { it.stableKey }.sortedBy { it.stableKey }) {
            "Memory evidence must be unique and deterministically ordered"
        }
        require(!lastObservedAt.isBefore(firstObservedAt)) {
            "Memory last observation cannot precede first observation"
        }
        require(observationCount >= evidence.size) {
            "Memory observation count cannot be smaller than evidence count"
        }
        require(tags.none { it.isBlank() }) { "Memory tags must not be blank" }
        when (kind) {
            MemoryKind.SEMANTIC -> require(semanticKind != null) {
                "Semantic memory requires a semantic subtype"
            }
            MemoryKind.EPISODIC,
            MemoryKind.PROCEDURAL -> require(semanticKind == null) {
                "Only semantic memory may have a semantic subtype"
            }
        }
        if (semanticKind == SemanticMemoryKind.PREFERENCE) {
            require(evidence.any { it.kind == MemoryEvidenceKind.USER_CONFIRMED }) {
                "Preference memory requires explicit user-confirmed evidence"
            }
            require(verification == MemoryVerificationStatus.VERIFIED) {
                "User-confirmed preference memory must be verified"
            }
        }
    }

    val isPreference: Boolean
        get() = semanticKind == SemanticMemoryKind.PREFERENCE

    companion object {
        fun create(
            key: MemoryKey,
            kind: MemoryKind,
            semanticKind: SemanticMemoryKind? = null,
            semanticKey: String,
            content: String,
            confidence: Double,
            verification: MemoryVerificationStatus,
            evidence: Iterable<MemoryEvidenceRef>,
            observationCount: Int = evidence.count(),
            tags: Set<String> = emptySet(),
        ): MemoryItem {
            val orderedEvidence = evidence.distinctBy { it.stableKey }.sortedBy { it.stableKey }
            require(orderedEvidence.isNotEmpty()) { "Memory evidence must not be empty" }
            val normalized = normalizeClaim(content)
            val id = MemoryId(
                "memory:" + StableFieldIds.fingerprint(
                    key.value,
                    normalized,
                    *orderedEvidence.map { it.stableKey }.toTypedArray(),
                )
            )
            return MemoryItem(
                id = id,
                key = key,
                kind = kind,
                semanticKind = semanticKind,
                semanticKey = semanticKey,
                content = content.trim(),
                normalizedClaim = normalized,
                confidence = confidence,
                verification = verification,
                evidence = orderedEvidence,
                firstObservedAt = orderedEvidence.minOf { it.observedAt },
                lastObservedAt = orderedEvidence.maxOf { it.observedAt },
                observationCount = observationCount,
                tags = tags.toSortedSet(),
            )
        }

        fun normalizeClaim(value: String): String = value
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }
}

data class MemorySourceConflict(
    val id: String,
    val thoughtNodeId: ThoughtNodeId,
    val photonId: PhotonId,
    val sourceRevision: Long,
    val fingerprints: List<String>,
) {
    init {
        require(id.isNotBlank()) { "Memory source conflict id must not be blank" }
        require(sourceRevision > 0) { "Memory source conflict revision must be positive" }
        require(fingerprints.size >= 2) { "Memory source conflict needs at least two variants" }
        require(fingerprints == fingerprints.distinct().sorted()) {
            "Memory source conflict fingerprints must be unique and ordered"
        }
    }
}

data class MemoryConflict(
    val id: String,
    val key: MemoryKey,
    val alternatives: List<MemoryItem>,
    val detectedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Memory conflict id must not be blank" }
        require(alternatives.size >= 2) { "Memory conflict requires at least two alternatives" }
        require(alternatives.all { it.key == key }) { "Memory conflict alternatives must share a key" }
        require(alternatives.map { it.normalizedClaim }.distinct().size >= 2) {
            "Memory conflict alternatives must disagree"
        }
        require(alternatives == alternatives.sortedBy { it.id.value }) {
            "Memory conflict alternatives must be deterministically ordered"
        }
    }
}

data class MemorySnapshot(
    val revision: Long,
    val items: List<MemoryItem>,
    val conflicts: List<MemoryConflict>,
    val sourceConflicts: List<MemorySourceConflict>,
    val capturedAt: Instant,
) {
    init {
        require(revision >= 0) { "Memory revision must not be negative" }
        require(items == items.sortedBy { it.id.value }) { "Memory items must be ordered" }
        require(conflicts == conflicts.sortedBy { it.id }) { "Memory conflicts must be ordered" }
        require(sourceConflicts == sourceConflicts.sortedBy { it.id }) {
            "Memory source conflicts must be ordered"
        }
        val conflictedKeys = conflicts.mapTo(mutableSetOf()) { it.key }
        require(items.none { it.key in conflictedKeys }) {
            "Conflicted memory keys cannot remain active"
        }
    }

    val contentFingerprint: String = StableFieldIds.fingerprint(
        "memory-snapshot/v1",
        *buildList {
            items.forEach { item ->
                add("item:${item.id.value}:${item.key.value}:${item.normalizedClaim}:${item.confidence}:${item.verification}")
                item.evidence.forEach { add("evidence:${it.stableKey}") }
            }
            conflicts.forEach { conflict ->
                add("conflict:${conflict.id}:${conflict.key.value}")
                conflict.alternatives.forEach { add("alternative:${it.id.value}") }
            }
            sourceConflicts.forEach { conflict ->
                add("source-conflict:${conflict.id}:${conflict.sourceRevision}")
                conflict.fingerprints.forEach { add(it) }
            }
        }.toTypedArray(),
    )

    val snapshotId: String = "memory-snapshot:$contentFingerprint"

    companion object {
        fun empty(capturedAt: Instant): MemorySnapshot = MemorySnapshot(
            revision = 0,
            items = emptyList(),
            conflicts = emptyList(),
            sourceConflicts = emptyList(),
            capturedAt = capturedAt,
        )
    }
}
