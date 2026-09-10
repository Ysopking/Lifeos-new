package app.lifeos.core.runtime.memory

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

data class MemoryConsolidationResult(
    val snapshot: MemorySnapshot,
    val mergedKeys: List<MemoryKey>,
    val conflictedKeys: List<MemoryKey>,
) {
    init {
        require(mergedKeys == mergedKeys.distinct().sortedBy { it.value }) {
            "Merged memory keys must be unique and ordered"
        }
        require(conflictedKeys == conflictedKeys.distinct().sortedBy { it.value }) {
            "Conflicted memory keys must be unique and ordered"
        }
    }
}

/**
 * Deterministically consolidates candidate projections without converting frequency into truth.
 * Repeated matching observations merge evidence but confidence never increases beyond the strongest
 * source candidate. Different claims for the same memory key remain an explicit conflict.
 */
class MemoryConsolidator {
    fun consolidate(
        report: MemoryProjectionReport,
        revision: Long,
        capturedAt: Instant,
    ): MemoryConsolidationResult {
        require(revision >= 0) { "Memory consolidation revision must not be negative" }

        val activeItems = mutableListOf<MemoryItem>()
        val conflicts = mutableListOf<MemoryConflict>()
        val mergedKeys = mutableListOf<MemoryKey>()
        val conflictedKeys = mutableListOf<MemoryKey>()

        report.candidates
            .distinctBy { it.id }
            .groupBy { it.key }
            .toSortedMap(compareBy<MemoryKey> { it.value })
            .forEach { (key, candidates) ->
                val claims = candidates
                    .groupBy { it.normalizedClaim }
                    .toSortedMap()
                    .mapValues { (_, sameClaim) -> mergeSameClaim(sameClaim) }

                if (claims.size == 1) {
                    val merged = claims.values.single()
                    activeItems += merged
                    if (candidates.size > 1) mergedKeys += key
                } else {
                    val alternatives = claims.values.sortedBy { it.id.value }
                    conflicts += MemoryConflict(
                        id = "memory-conflict:" + StableFieldIds.fingerprint(
                            key.value,
                            *alternatives.map { it.id.value }.toTypedArray(),
                        ),
                        key = key,
                        alternatives = alternatives,
                        detectedAt = capturedAt,
                    )
                    conflictedKeys += key
                }
            }

        val snapshot = MemorySnapshot(
            revision = revision,
            items = activeItems.sortedBy { it.id.value },
            conflicts = conflicts.sortedBy { it.id },
            sourceConflicts = report.sourceConflicts.distinctBy { it.id }.sortedBy { it.id },
            capturedAt = capturedAt,
        )
        return MemoryConsolidationResult(
            snapshot = snapshot,
            mergedKeys = mergedKeys.distinct().sortedBy { it.value },
            conflictedKeys = conflictedKeys.distinct().sortedBy { it.value },
        )
    }

    private fun mergeSameClaim(candidates: List<MemoryItem>): MemoryItem {
        require(candidates.isNotEmpty()) { "Cannot merge empty memory candidates" }
        val first = candidates.first()
        require(candidates.all { it.key == first.key }) { "Merged candidates must share a key" }
        require(candidates.all { it.kind == first.kind }) { "Merged candidates must share memory kind" }
        require(candidates.all { it.semanticKind == first.semanticKind }) {
            "Merged candidates must share semantic subtype"
        }
        require(candidates.all { it.semanticKey == first.semanticKey }) {
            "Merged candidates must share semantic key"
        }
        require(candidates.all { it.normalizedClaim == first.normalizedClaim }) {
            "Merged candidates must share normalized claim"
        }

        val evidence = candidates
            .flatMap { it.evidence }
            .distinctBy { it.stableKey }
            .sortedBy { it.stableKey }
        val representative = candidates.sortedWith(
            compareByDescending<MemoryItem> { verificationRank(it.verification) }
                .thenByDescending { it.confidence }
                .thenBy { it.content }
                .thenBy { it.id.value }
        ).first()

        return MemoryItem.create(
            key = first.key,
            kind = first.kind,
            semanticKind = first.semanticKind,
            semanticKey = first.semanticKey,
            content = representative.content,
            confidence = candidates.maxOf { it.confidence },
            verification = candidates.maxBy { verificationRank(it.verification) }.verification,
            evidence = evidence,
            observationCount = candidates.distinctBy { it.id }.sumOf { it.observationCount },
            tags = candidates.flatMapTo(sortedSetOf()) { it.tags },
        )
    }

    private fun verificationRank(status: MemoryVerificationStatus): Int = when (status) {
        MemoryVerificationStatus.CONFLICTED -> 0
        MemoryVerificationStatus.UNVERIFIED -> 1
        MemoryVerificationStatus.OBSERVED -> 2
        MemoryVerificationStatus.VERIFIED -> 3
    }
}
