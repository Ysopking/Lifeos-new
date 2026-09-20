package app.lifeos.core.language

data class SemanticSearchQueryPlan(
    val primaryQuery: String,
    val contextTerms: Set<String>,
    val alternatives: List<String>,
) {
    init {
        require(primaryQuery.isNotBlank())
        require(contextTerms.none { it.isBlank() })
        require(alternatives.none { it.isBlank() })
        require(alternatives.distinct().size == alternatives.size)
    }
}

/**
 * Converts LIFEOS' own semantic structures into bounded search queries without an LLM.
 * Only the current GoalFrame participates; historical/private corpus terms are never injected here.
 */
class SemanticSearchQueryPlanner {
    fun plan(goal: GoalFrame): SemanticSearchQueryPlan {
        val raw = goal.objective.substringAfter(": ", goal.objective).trim()
            .ifBlank { goal.objective }
        val objectiveTerms = surfaceTerms(raw)
            .filterNot { SemanticSearchTerms.normalizeToken(it) in directiveWords }
        val entityTerms = goal.entities
            .map { it.normalizedValue.trim() }
            .filter { it.isNotBlank() }
        val domainTerms = goal.domainSemanticGraph.nodes
            .asSequence()
            .sortedByDescending { it.confidence }
            .map { it.value.substringBefore(':').trim() }
            .filter { it.isNotBlank() }
            .take(6)
            .toList()

        val primaryParts = (objectiveTerms + entityTerms + domainTerms)
            .flatMap(::surfaceTerms)
            .distinctBy(SemanticSearchTerms::normalizeToken)
            .take(MAX_PRIMARY_TERMS)
        val primary = primaryParts.joinToString(" ").ifBlank { raw }

        val context = buildSet {
            addAll(SemanticSearchTerms.expandedTokens(primary))
            goal.semanticEntitiesV2.forEach { addAll(SemanticSearchTerms.expandedTokens(it.normalizedValue)) }
            goal.domainSemanticGraph.nodes
                .filter { it.confidence >= 0.55 }
                .take(MAX_DOMAIN_CONTEXT)
                .forEach { addAll(SemanticSearchTerms.expandedTokens(it.value)) }
            goal.quantityTemporal.quantities.forEach { quantity ->
                quantity.value?.toPlainString()?.let(::add)
                quantity.lowerBound?.toPlainString()?.let(::add)
                quantity.upperBound?.toPlainString()?.let(::add)
                quantity.currency?.currencyCode?.let { add(it.lowercase()) }
                quantity.unit?.let { add(SemanticSearchTerms.normalizeToken(it)) }
            }
        }.filter { it.isNotBlank() }.take(MAX_CONTEXT_TERMS).toSortedSet()

        val alternatives = buildList {
            val entityFocused = entityTerms.flatMap(::surfaceTerms)
                .distinctBy(SemanticSearchTerms::normalizeToken)
                .take(6)
                .joinToString(" ")
            if (entityFocused.isNotBlank() && entityFocused != primary) add(entityFocused)

            val domainFocused = (entityTerms + domainTerms)
                .flatMap(::surfaceTerms)
                .distinctBy(SemanticSearchTerms::normalizeToken)
                .take(10)
                .joinToString(" ")
            if (domainFocused.isNotBlank() && domainFocused != primary && domainFocused !in this) {
                add(domainFocused)
            }
        }.take(MAX_ALTERNATIVES)

        return SemanticSearchQueryPlan(
            primaryQuery = primary,
            contextTerms = context,
            alternatives = alternatives,
        )
    }

    private fun surfaceTerms(value: String): List<String> = TERM_REGEX.findAll(value)
        .map { it.value }
        .filter { SemanticSearchTerms.normalizeToken(it).length >= 2 }
        .toList()

    private companion object {
        const val MAX_PRIMARY_TERMS = 14
        const val MAX_CONTEXT_TERMS = 48
        const val MAX_DOMAIN_CONTEXT = 8
        const val MAX_ALTERNATIVES = 3
        val TERM_REGEX = Regex("[\\p{L}\\p{N}._-]+")
        val directiveWords = setOf(
            "suche", "finde", "recherchiere", "deepsearch", "search", "find", "research", "lookup",
            "pruefe", "prüfe", "check", "nach", "bitte", "mir", "fuer", "für", "zu", "ueber", "über",
        ).map(SemanticSearchTerms::normalizeToken).toSet()
    }
}
