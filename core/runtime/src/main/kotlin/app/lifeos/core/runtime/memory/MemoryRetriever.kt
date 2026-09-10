package app.lifeos.core.runtime.memory

data class MemoryQuery(
    val text: String = "",
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val semanticKeys: Set<String> = emptySet(),
    val requiredTags: Set<String> = emptySet(),
    val minConfidence: Double = 0.0,
    val limit: Int = 20,
    val includeConflicts: Boolean = true,
    val includeSourceConflicts: Boolean = true,
) {
    init {
        require(kinds.isNotEmpty()) { "Memory query must include at least one memory kind" }
        require(semanticKeys.none { it.isBlank() }) { "Memory query semantic keys must not be blank" }
        require(requiredTags.none { it.isBlank() }) { "Memory query tags must not be blank" }
        require(minConfidence in 0.0..1.0) { "Memory query min confidence must be in 0..1" }
        require(limit > 0) { "Memory query limit must be positive" }
    }
}

data class MemoryMatch(
    val item: MemoryItem,
    val score: Double,
    val reasons: List<String>,
) {
    init {
        require(score in 0.0..1.0) { "Memory match score must be in 0..1" }
        require(reasons.isNotEmpty()) { "Memory match requires an explanation" }
    }
}

data class MemoryConflictMatch(
    val conflict: MemoryConflict,
    val score: Double,
    val reasons: List<String>,
) {
    init {
        require(score in 0.0..1.0) { "Memory conflict score must be in 0..1" }
        require(reasons.isNotEmpty()) { "Memory conflict match requires an explanation" }
    }
}

data class MemoryRetrievalResult(
    val matches: List<MemoryMatch>,
    val conflicts: List<MemoryConflictMatch>,
    val sourceConflicts: List<MemorySourceConflict>,
) {
    init {
        require(matches == matches.sortedWith(matchOrdering())) {
            "Memory matches must be deterministically ordered"
        }
        require(conflicts == conflicts.sortedWith(conflictOrdering())) {
            "Memory conflict matches must be deterministically ordered"
        }
        require(sourceConflicts == sourceConflicts.sortedBy { it.id }) {
            "Memory source conflicts must be deterministically ordered"
        }
    }

    companion object {
        internal fun matchOrdering(): Comparator<MemoryMatch> =
            compareByDescending<MemoryMatch> { it.score }.thenBy { it.item.id.value }

        internal fun conflictOrdering(): Comparator<MemoryConflictMatch> =
            compareByDescending<MemoryConflictMatch> { it.score }.thenBy { it.conflict.id }
    }
}

/** Read-only deterministic retrieval. Conflicted keys are surfaced separately, never as facts. */
class MemoryRetriever {
    fun retrieve(snapshot: MemorySnapshot, query: MemoryQuery): MemoryRetrievalResult {
        val matches = snapshot.items
            .asSequence()
            .filter { it.kind in query.kinds }
            .filter { it.confidence >= query.minConfidence }
            .filter {
                query.semanticKeys.isEmpty() ||
                    query.semanticKeys.any { key -> key.equals(it.semanticKey, ignoreCase = true) }
            }
            .filter { item ->
                query.requiredTags.all { required ->
                    item.tags.any { it.equals(required, ignoreCase = true) }
                }
            }
            .mapNotNull { score(it, query)?.let { scored -> MemoryMatch(it, scored.first, scored.second) } }
            .sortedWith(MemoryRetrievalResult.matchOrdering())
            .take(query.limit)
            .toList()

        val conflicts = if (query.includeConflicts) {
            snapshot.conflicts.mapNotNull { conflict ->
                val best = conflict.alternatives
                    .asSequence()
                    .filter { it.kind in query.kinds }
                    .filter { it.confidence >= query.minConfidence }
                    .filter {
                        query.semanticKeys.isEmpty() ||
                            query.semanticKeys.any { key -> key.equals(it.semanticKey, ignoreCase = true) }
                    }
                    .filter { item ->
                        query.requiredTags.all { required ->
                            item.tags.any { it.equals(required, ignoreCase = true) }
                        }
                    }
                    .mapNotNull { score(it, query) }
                    .maxByOrNull { it.first }
                    ?: return@mapNotNull null
                MemoryConflictMatch(
                    conflict = conflict,
                    score = best.first,
                    reasons = (best.second + "conflicted-key-not-authoritative").distinct(),
                )
            }.sortedWith(MemoryRetrievalResult.conflictOrdering()).take(query.limit)
        } else {
            emptyList()
        }

        return MemoryRetrievalResult(
            matches = matches,
            conflicts = conflicts,
            sourceConflicts = if (query.includeSourceConflicts) snapshot.sourceConflicts else emptyList(),
        )
    }

    private fun score(item: MemoryItem, query: MemoryQuery): Pair<Double, List<String>>? {
        val queryTokens = tokenize(query.text)
        val itemTokens = buildSet {
            addAll(tokenize(item.content))
            addAll(tokenize(item.semanticKey))
            item.tags.forEach { addAll(tokenize(it)) }
        }

        val lexical = if (queryTokens.isEmpty()) {
            1.0
        } else {
            val matched = queryTokens.count { it in itemTokens }
            if (matched == 0) return null
            matched.toDouble() / queryTokens.size.toDouble()
        }
        val verification = when (item.verification) {
            MemoryVerificationStatus.VERIFIED -> 1.0
            MemoryVerificationStatus.OBSERVED -> 0.75
            MemoryVerificationStatus.UNVERIFIED -> 0.5
            MemoryVerificationStatus.CONFLICTED -> 0.0
        }
        val score = (0.50 * lexical + 0.35 * item.confidence + 0.15 * verification)
            .coerceIn(0.0, 1.0)
        val reasons = buildList {
            add(if (queryTokens.isEmpty()) "unfiltered-memory-query" else "lexical-overlap")
            add("confidence=${item.confidence}")
            add("verification=${item.verification.name.lowercase()}")
            if (item.isPreference) add("explicit-user-confirmed-preference")
        }
        return score to reasons
    }

    private fun tokenize(value: String): Set<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() }
        .toSet()
}
