package app.lifeos.core.language

import java.util.Locale

data class IndexedReferenceCandidate(
    val item: LanguageContextItem,
    val indexScore: Double,
) {
    init {
        require(indexScore.isFinite() && indexScore in 0.0..1.0)
    }
}

/**
 * Immutable bounded semantic index over an already index-retrieved [LanguageContext].
 *
 * Building this index never touches the Photon repository. Candidate lookup uses metadata buckets
 * and returns a hard-bounded set before the expensive reference scorer runs.
 */
class ReferenceCandidateIndexV3(
    context: LanguageContext,
) {
    private val items = context.items
        .sortedWith(
            compareByDescending<LanguageContextItem> { it.createdAt }
                .thenByDescending { it.revisionRef?.revision ?: 0L }
                .thenBy { it.photonId.value }
        )

    private val byId = items.groupBy { it.photonId }
    private val byKind = items.groupBy { normalize(it.kind) }
    private val byTag = items
        .flatMap { item -> item.tags.map { normalize(it) to item } }
        .groupBy({ it.first }, { it.second })
    private val bySemanticType = items
        .flatMap { item -> item.semanticTypes.map { normalize(it) to item } }
        .groupBy({ it.first }, { it.second })
    private val byTerm = items
        .flatMap { item ->
            (item.normalizedTerms + item.conceptIds + item.relationKeys)
                .map { normalize(it) to item }
        }
        .groupBy({ it.first }, { it.second })

    fun candidates(
        expression: ReferenceExpression,
        limit: Int = DEFAULT_LIMIT,
    ): List<IndexedReferenceCandidate> {
        require(limit in 1..MAX_LIMIT)
        if (expression.kind == ReferenceKind.EXPLICIT_ID) {
            val id = runCatching { app.lifeos.core.model.PhotonId(expression.rawText) }.getOrNull()
                ?: return emptyList()
            return byId[id]
                .orEmpty()
                .sortedByDescending { it.revisionRef?.revision ?: 0L }
                .take(limit)
                .map { IndexedReferenceCandidate(it, 1.0) }
        }

        val scored = linkedMapOf<String, Pair<LanguageContextItem, Double>>()
        fun add(candidates: Iterable<LanguageContextItem>, score: Double) {
            candidates.forEach { item ->
                val key = item.photonId.value + "@" + (item.revisionRef?.revision ?: 0L)
                val old = scored[key]
                val combined = ((old?.second ?: 0.0) + score).coerceIn(0.0, 1.0)
                scored[key] = item to combined
            }
        }

        expression.preferredKinds
            .map(::normalize)
            .sorted()
            .forEach { preferred ->
                add(byKind[preferred].orEmpty(), 0.30)
                add(byTag[preferred].orEmpty(), 0.25)
                add(bySemanticType[preferred].orEmpty(), 0.30)
                bySemanticType.entries
                    .asSequence()
                    .filter { (type, _) -> type.endsWith(":$preferred") || type.endsWith(".$preferred") }
                    .take(MAX_SUFFIX_BUCKETS)
                    .forEach { (_, values) -> add(values, 0.22) }
            }

        referenceTerms(expression.rawText).sorted().forEach { term ->
            add(byTerm[term].orEmpty(), 0.45)
        }

        when (expression.kind) {
            ReferenceKind.LAST_RESULT ->
                add(byTag["result"].orEmpty(), 0.35)
            ReferenceKind.PREVIOUS,
            ReferenceKind.THIS,
            ReferenceKind.THAT ->
                add(items.asSequence().filter { it.active }.take(ACTIVE_FALLBACK), 0.15)
            ReferenceKind.OTHER ->
                add(items.asSequence().filterNot { it.active }.take(RECENT_FALLBACK), 0.10)
            ReferenceKind.YESTERDAY,
            ReferenceKind.EXPLICIT_ID -> Unit
        }

        if (scored.size < limit) {
            add(items.take(RECENT_FALLBACK), 0.05)
        }

        return scored.values
            .asSequence()
            .map { (item, score) -> IndexedReferenceCandidate(item, score) }
            .sortedWith(
                compareByDescending<IndexedReferenceCandidate> { it.indexScore }
                    .thenByDescending { it.item.createdAt }
                    .thenByDescending { it.item.revisionRef?.revision ?: 0L }
                    .thenBy { it.item.photonId.value }
            )
            .take(limit)
            .toList()
    }

    private fun referenceTerms(value: String): Set<String> =
        TERM_REGEX.findAll(value)
            .map { normalize(it.value) }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace("ß", "ss").trim()

    private companion object {
        const val DEFAULT_LIMIT = 32
        const val MAX_LIMIT = 64
        const val RECENT_FALLBACK = 16
        const val ACTIVE_FALLBACK = 12
        const val MAX_SUFFIX_BUCKETS = 8
        val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf(
            "das", "die", "der", "den", "dem", "dies", "diese", "dieses", "diesen",
            "andere", "anderen", "bitte", "mit", "und", "oder", "mach", "mache",
            "it", "this", "that", "the", "other", "with", "and", "or", "please", "make",
        )
    }
}
