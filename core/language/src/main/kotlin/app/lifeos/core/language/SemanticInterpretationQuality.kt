package app.lifeos.core.language

data class SemanticInterpretationQuality(
    val evidenceStrength: Double,
    val interpretationMargin: Double,
    val completeness: Double,
    val contradictionCount: Int,
    val ambiguityCount: Int,
    val executionReadiness: Double,
) {
    init {
        require(evidenceStrength.isFinite() && evidenceStrength in 0.0..1.0)
        require(interpretationMargin.isFinite() && interpretationMargin in 0.0..1.0)
        require(completeness.isFinite() && completeness in 0.0..1.0)
        require(contradictionCount >= 0)
        require(ambiguityCount >= 0)
        require(executionReadiness.isFinite() && executionReadiness in 0.0..1.0)
    }

    companion object {
        fun unknown(): SemanticInterpretationQuality = SemanticInterpretationQuality(
            evidenceStrength = 0.0,
            interpretationMargin = 0.0,
            completeness = 0.0,
            contradictionCount = 0,
            ambiguityCount = 0,
            executionReadiness = 0.0,
        )
    }
}

class SemanticInterpretationQualityEvaluator {
    fun evaluate(
        intentEvidence: List<IntentEvidence>,
        actionGraph: SemanticActionGraph,
        ambiguities: List<Ambiguity>,
    ): SemanticInterpretationQuality {
        val top = intentEvidence.firstOrNull()?.score ?: 0.0
        val second = intentEvidence.getOrNull(1)?.score ?: 0.0
        val margin = (top - second).coerceIn(0.0, 1.0)

        val actionable = actionGraph.nodes.filter {
            it.type in setOf(SemanticActionNodeType.ACTION, SemanticActionNodeType.QUERY)
        }
        val completeness = if (actionable.isEmpty()) {
            if (actionGraph.nodes.isEmpty()) 0.0 else 1.0
        } else {
            actionable.map { node ->
                val required = node.requiredRoles.size
                if (required == 0) 1.0
                else (required - node.unresolvedRoles.size).toDouble() / required.toDouble()
            }.average()
        }.coerceIn(0.0, 1.0)

        val contradictionCount = ambiguities.count {
            it.code in CONTRADICTION_CODES
        }
        val ambiguityCount = ambiguities.count { it.severity >= 0.50 }
        val executionReadiness = actionGraph.executableNodes
            .maxOfOrNull { it.executionReadiness }
            ?: actionGraph.nodes
                .filter { it.type == SemanticActionNodeType.QUERY }
                .maxOfOrNull { it.frame.confidence }
                ?.times(0.25)
            ?: 0.0

        return SemanticInterpretationQuality(
            evidenceStrength = top,
            interpretationMargin = margin,
            completeness = completeness,
            contradictionCount = contradictionCount,
            ambiguityCount = ambiguityCount,
            executionReadiness = executionReadiness.coerceIn(0.0, 1.0),
        )
    }

    private companion object {
        val CONTRADICTION_CODES = setOf(
            "command_vs_question",
            "quoted_action",
            "negated_action",
            "conditional_action",
            "role_ambiguity",
            "reference_semantic_mismatch",
            "predicate_competition",
        )
    }
}
