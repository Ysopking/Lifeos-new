package app.lifeos.core.language

enum class ClarificationReason {
    REFERENCE,
    INTENT,
    ROLE,
    CONDITION,
    INTERPRETATION,
}

data class ClarificationPlan(
    val required: Boolean,
    val reason: ClarificationReason?,
    val questionDe: String?,
    val questionEn: String?,
    val alternatives: List<String>,
) {
    init {
        require(!required || reason != null)
        require(!required || !questionDe.isNullOrBlank() || !questionEn.isNullOrBlank())
    }

    companion object {
        fun none(): ClarificationPlan =
            ClarificationPlan(false, null, null, null, emptyList())
    }
}

class ClarificationEngine {
    fun build(
        ambiguities: List<Ambiguity>,
        graph: SemanticActionGraph,
        lattice: SemanticInterpretationLattice,
    ): ClarificationPlan {
        val reference = ambiguities.firstOrNull {
            it.code == "reference_competition" && it.severity >= 0.65
        }
        if (reference != null) {
            return ClarificationPlan(
                required = true,
                reason = ClarificationReason.REFERENCE,
                questionDe = "Welches davon meinst du genau?",
                questionEn = "Which one exactly do you mean?",
                alternatives = reference.alternatives.take(4),
            )
        }

        val unresolvedExternal = graph.nodes
            .filter { it.externalSideEffect }
            .flatMap { it.unresolvedExternalRoles }
            .firstOrNull()
        if (unresolvedExternal != null) {
            val label = unresolvedExternal.name.lowercase()
            return ClarificationPlan(
                required = true,
                reason = ClarificationReason.ROLE,
                questionDe = "Für die Aktion fehlt noch eine eindeutige Angabe: " + label + ".",
                questionEn = "The action still needs an unambiguous value for: " + label + ".",
                alternatives = emptyList(),
            )
        }

        if (
            lattice.candidates.size > 1 &&
            !lattice.converged &&
            lattice.margin < 0.04 &&
            graph.nodes.any { it.type == SemanticActionNodeType.ACTION }
        ) {
            return ClarificationPlan(
                required = true,
                reason = ClarificationReason.INTERPRETATION,
                questionDe = "Welche Bedeutung meinst du?",
                questionEn = "Which meaning do you mean?",
                alternatives = lattice.candidates.take(3).map { it.intent.name },
            )
        }

        return ClarificationPlan.none()
    }
}
