package app.lifeos.core.language

/**
 * Resolves coarse scope cues to explicit semantic targets.
 *
 * Scope is never interpreted as clause-wide truth. A scope targets either one predicate, one role
 * on that predicate, or one stable semantic relation.
 */
class TargetBoundScopeEngine {
    fun bind(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        node: SemanticActionNode,
        edges: List<SemanticActionEdge>,
    ): List<SemanticScope> = node.frame.scopeTypes.map { type ->
        val relation = relationTarget(type, node.id, edges)
        val role = roleTarget(type, node.frame)
        val target = when {
            relation != null -> SemanticScopeTarget(
                kind = SemanticScopeTargetKind.RELATION,
                edgeId = relation.id,
            )
            role != null -> SemanticScopeTarget(
                kind = SemanticScopeTargetKind.ROLE,
                nodeId = node.id,
                role = role,
            )
            else -> SemanticScopeTarget(
                kind = SemanticScopeTargetKind.PREDICATE,
                nodeId = node.id,
            )
        }
        SemanticScope(
            type = type,
            targetNodeIds = setOf(node.id),
            span = clauseSpan(utterance, graph, node.frame.clauseId),
            cue = scopeCue(type, utterance, graph, node.frame.clauseId),
            confidence = node.frame.confidence,
            targets = setOf(target),
        )
    }

    private fun relationTarget(
        type: ScopeType,
        nodeId: SemanticNodeId,
        edges: List<SemanticActionEdge>,
    ): SemanticActionEdge? {
        val edgeType = when (type) {
            ScopeType.CONDITION -> SemanticActionEdgeType.IF
            ScopeType.CONTRAST -> SemanticActionEdgeType.ELSE
            else -> return null
        }
        return edges
            .asSequence()
            .filter { it.type == edgeType && (it.from == nodeId || it.to == nodeId) }
            .sortedBy { it.id.value }
            .firstOrNull()
    }

    private fun roleTarget(
        type: ScopeType,
        frame: PredicateFrame,
    ): SemanticRole? {
        if (type != ScopeType.EXCLUSION) return null
        return ROLE_SCOPE_PRIORITY.firstOrNull { it in frame.roles }
    }

    private fun clauseSpan(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        clauseId: Int,
    ): TextSpan {
        val clause = graph.clauses.single { it.id == clauseId }
        val first = utterance.tokens[clause.tokenStart]
        val last = utterance.tokens[clause.tokenEndExclusive - 1]
        return TextSpan(first.start, last.endExclusive)
    }

    private fun scopeCue(
        type: ScopeType,
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        clauseId: Int,
    ): String {
        val clause = graph.clauses.single { it.id == clauseId }
        val words = utterance.tokens
            .subList(clause.tokenStart, clause.tokenEndExclusive)
            .map { it.normalized }
        val candidates = when (type) {
            ScopeType.NEGATION -> setOf("nicht", "nie", "niemals", "kein", "keine", "not", "never", "no")
            ScopeType.MODALITY -> setOf(
                "muss", "soll", "darf", "kann", "würde", "wuerde",
                "must", "should", "may", "can", "would",
            )
            ScopeType.CONDITION -> setOf("wenn", "falls", "sofern", "if", "unless")
            ScopeType.QUOTATION -> setOf("quote")
            ScopeType.HYPOTHETICAL -> setOf(
                "würde", "wuerde", "könnte", "koennte", "would", "could", "hypothetisch",
            )
            ScopeType.CONTRAST -> setOf(
                "aber", "sondern", "stattdessen", "but", "rather", "instead",
            )
            ScopeType.EXCLUSION -> setOf("nicht", "kein", "ohne", "not", "no", "without")
        }
        return words.firstOrNull { it in candidates } ?: type.name.lowercase()
    }

    private companion object {
        val ROLE_SCOPE_PRIORITY = listOf(
            SemanticRole.OBJECT,
            SemanticRole.AMOUNT,
            SemanticRole.DATE,
            SemanticRole.TIME,
            SemanticRole.LOCATION,
            SemanticRole.RECIPIENT,
            SemanticRole.ATTRIBUTE,
        )
    }
}
