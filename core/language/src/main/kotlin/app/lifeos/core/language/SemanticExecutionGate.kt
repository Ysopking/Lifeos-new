package app.lifeos.core.language

data class SemanticExecutionDecision(
    val allowed: Boolean,
    val reason: String,
    val nodeId: SemanticNodeId? = null,
) {
    init { require(reason.isNotBlank()) }
}

/**
 * Single semantic admission gate shared by routing/execution boundaries.
 *
 * Topic intent is never sufficient. Action-like goals need an executable PredicateFrame;
 * questions need an explicit QueryNode. Conversation/unknown goals never authorize an action.
 */
object SemanticExecutionGate {
    fun evaluate(goal: GoalFrame): SemanticExecutionDecision {
        val graph = goal.semanticActionGraph
        return when (goal.intent) {
            IntentType.QUERY -> {
                val query = graph.nodes
                    .filter { it.type == SemanticActionNodeType.QUERY }
                    .maxByOrNull { it.frame.confidence }
                if (query == null) {
                    SemanticExecutionDecision(false, "semantic-query-node-missing")
                } else if (query.frame.quoted) {
                    SemanticExecutionDecision(false, "semantic-query-is-quoted", query.id)
                } else {
                    SemanticExecutionDecision(true, "semantic-query-ready", query.id)
                }
            }

            IntentType.CONVERSATION ->
                SemanticExecutionDecision(false, "conversation-has-no-executable-action")

            IntentType.UNKNOWN ->
                SemanticExecutionDecision(false, "semantic-action-unknown")

            else -> {
                val node = graph.executableNodeFor(goal.intent)
                if (node != null) {
                    SemanticExecutionDecision(true, "semantic-action-ready", node.id)
                } else {
                    val predicate = goal.intent.toPredicateConcept()
                    val candidate = graph.nodes
                        .filter { it.frame.predicate == predicate }
                        .maxByOrNull { it.executionReadiness }
                    val reason = when {
                        candidate == null -> "semantic-predicate-missing"
                        candidate.frame.speechAct.type == SpeechActType.QUESTION -> "semantic-action-is-question"
                        candidate.frame.quoted -> "semantic-action-is-quoted"
                        candidate.frame.negated -> "semantic-action-is-negated"
                        candidate.frame.hypothetical -> "semantic-action-is-hypothetical"
                        candidate.unresolvedCondition -> "semantic-action-condition-unresolved"
                        candidate.unresolvedReference -> "semantic-action-reference-unresolved"
                        candidate.unresolvedRoles.isNotEmpty() ->
                            "semantic-action-arguments-unresolved:" +
                                candidate.unresolvedRoles.map { it.name.lowercase() }.sorted().joinToString(",")
                        else -> "semantic-action-readiness-below-threshold"
                    }
                    SemanticExecutionDecision(false, reason, candidate?.id)
                }
            }
        }
    }

    fun externalEffectAllowed(goal: GoalFrame): Boolean {
        val decision = evaluate(goal)
        if (!decision.allowed) return false
        val nodeId = decision.nodeId ?: return false
        return goal.semanticActionGraph.nodes
            .singleOrNull { it.id == nodeId }
            ?.let { it.executable && it.externalSideEffect }
            ?: false
    }
}
