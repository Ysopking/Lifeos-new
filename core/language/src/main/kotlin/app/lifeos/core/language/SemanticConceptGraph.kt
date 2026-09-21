package app.lifeos.core.language

enum class ConceptRelationType {
    SYNONYM,
    NEAR_SYNONYM,
    IS_A,
    PART_OF,
    REQUIRES,
    CONTRADICTS,
    RELATED,
}

data class ConceptRelation(
    val left: String,
    val right: String,
    val type: ConceptRelationType,
    val weight: Double,
) {
    init {
        require(left.isNotBlank())
        require(right.isNotBlank())
        require(left != right)
        require(weight.isFinite() && weight in 0.0..1.0)
    }
}

class SemanticConceptGraph(
    relations: Collection<ConceptRelation> = defaultRelations(),
) {
    private val relations = relations.toList()

    fun force(left: LinguisticConcept, right: LinguisticConcept): Double {
        val matches = relations.filter {
            (it.left == left.id && it.right == right.id) ||
                (it.right == left.id && it.left == right.id)
        }
        return matches.sumOf {
            when (it.type) {
                ConceptRelationType.CONTRADICTS -> -it.weight
                else -> it.weight
            }
        }.coerceIn(-1.0, 1.0)
    }

    companion object {
        fun defaultRelations(): List<ConceptRelation> = listOf(
            ConceptRelation("continue", "implement", ConceptRelationType.RELATED, 0.35),
            ConceptRelation("create.image", "image", ConceptRelationType.REQUIRES, 0.70),
            ConceptRelation("transform.image", "image", ConceptRelationType.REQUIRES, 0.78),
            ConceptRelation("search", "result", ConceptRelationType.RELATED, 0.42),
            ConceptRelation("communicate", "person", ConceptRelationType.REQUIRES, 0.45),
            ConceptRelation("schedule", "time", ConceptRelationType.REQUIRES, 0.55),
            ConceptRelation("store.memory", "result", ConceptRelationType.RELATED, 0.30),
        )
    }
}
