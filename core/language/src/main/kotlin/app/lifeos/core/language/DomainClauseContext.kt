package app.lifeos.core.language

data class DomainClauseContext(
    val clauseId: Int,
    val tokenStart: Int,
    val tokenEndExclusive: Int,
    val span: TextSpan,
    val entities: List<SemanticEntityV2>,
    val quantities: List<SemanticQuantityV2>,
    val temporals: List<SemanticTemporalValue>,
    val frames: List<PredicateFrame>,
) {
    init {
        require(clauseId >= 0)
        require(tokenStart >= 0)
        require(tokenEndExclusive > tokenStart)
    }
}

class DomainClauseContextBuilder {
    fun build(
        utterance: NormalizedUtterance,
        semanticGraph: LanguageSemanticGraph,
        entities: List<SemanticEntityV2>,
        quantityTemporal: QuantityTemporalResult,
        actionGraph: SemanticActionGraph,
    ): List<DomainClauseContext> {
        if (semanticGraph.clauses.isEmpty()) {
            val first = utterance.tokens.firstOrNull() ?: return emptyList()
            val last = utterance.tokens.last()
            return listOf(
                DomainClauseContext(
                    clauseId = 0,
                    tokenStart = 0,
                    tokenEndExclusive = utterance.tokens.size,
                    span = TextSpan(first.start, last.endExclusive),
                    entities = entities.sortedBy { it.tokenStart },
                    quantities = quantityTemporal.quantities.sortedBy { it.span.start },
                    temporals = quantityTemporal.temporals.sortedBy { it.span.start },
                    frames = actionGraph.nodes.map { it.frame }.distinctBy { it.nodeId },
                )
            )
        }

        return semanticGraph.clauses.sortedBy { it.id }.map { clause ->
            val first = utterance.tokens[clause.tokenStart]
            val last = utterance.tokens[clause.tokenEndExclusive - 1]
            val span = TextSpan(first.start, last.endExclusive)
            DomainClauseContext(
                clauseId = clause.id,
                tokenStart = clause.tokenStart,
                tokenEndExclusive = clause.tokenEndExclusive,
                span = span,
                entities = entities
                    .filter { it.tokenStart < clause.tokenEndExclusive && it.tokenEndExclusive > clause.tokenStart }
                    .sortedBy { it.tokenStart },
                quantities = quantityTemporal.quantities
                    .filter { it.span.overlaps(span) || span.contains(it.span) }
                    .sortedBy { it.span.start },
                temporals = quantityTemporal.temporals
                    .filter { it.span.overlaps(span) || span.contains(it.span) }
                    .sortedBy { it.span.start },
                frames = actionGraph.nodes
                    .map { it.frame }
                    .filter { it.clauseId == clause.id }
                    .distinctBy { it.nodeId }
                    .sortedBy { it.nodeId.value },
            )
        }
    }
}
