package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class SyntaxRelation {
    ROOT,
    SUBJECT,
    OBJECT,
    INDIRECT_OBJECT,
    AUXILIARY,
    MODIFIER,
    PREPOSITIONAL_OBJECT,
    NEGATION,
    PARTICLE,
    CLAUSE_MARKER,
}

data class SyntaxArc(
    val headTokenIndex: Int,
    val dependentTokenIndex: Int,
    val relation: SyntaxRelation,
    val confidence: Double,
) {
    init {
        require(headTokenIndex >= 0)
        require(dependentTokenIndex >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class DependencySyntaxGraph(
    val arcs: List<SyntaxArc>,
    val rootTokenIndices: Set<Int>,
    val fingerprint: String,
) {
    init {
        require(fingerprint.isNotBlank())
        require(rootTokenIndices.all { it >= 0 })
    }

    fun dependents(head: Int, relation: SyntaxRelation? = null): List<Int> =
        arcs.asSequence()
            .filter { it.headTokenIndex == head && (relation == null || it.relation == relation) }
            .map { it.dependentTokenIndex }
            .distinct()
            .sorted()
            .toList()

    companion object {
        fun empty(): DependencySyntaxGraph = DependencySyntaxGraph(
            arcs = emptyList(),
            rootTokenIndices = emptySet(),
            fingerprint = StableCognitiveIds.fingerprint("dependency-syntax/v1", "empty"),
        )
    }
}

class DeterministicDependencySyntaxParser(
    private val morphology: GermanMorphologyEngine = GermanMorphologyEngine(),
) {
    fun parse(
        utterance: NormalizedUtterance,
        semanticGraph: LanguageSemanticGraph,
    ): DependencySyntaxGraph {
        val arcs = mutableListOf<SyntaxArc>()
        val roots = linkedSetOf<Int>()

        semanticGraph.clauses.forEach { clause ->
            val indices = (clause.tokenStart until clause.tokenEndExclusive).toList()
            val wordIndices = indices.filter { utterance.tokens[it].kind == TokenKind.WORD }
            if (wordIndices.isEmpty()) return@forEach

            val root = wordIndices.firstOrNull { index ->
                val normalized = utterance.tokens[index].normalized
                normalized in VERB_FORMS ||
                    morphology.candidates(normalized).any { it in VERB_FORMS }
            } ?: wordIndices.first()

            roots += root
            arcs += SyntaxArc(root, root, SyntaxRelation.ROOT, 1.0)

            wordIndices.forEach { index ->
                if (index == root) return@forEach
                val word = utterance.tokens[index].normalized
                val previous = utterance.tokens.getOrNull(index - 1)?.normalized
                val relation = when {
                    word in NEGATION -> SyntaxRelation.NEGATION
                    word in PARTICLES -> SyntaxRelation.PARTICLE
                    previous in RECIPIENT_PREPOSITIONS -> SyntaxRelation.INDIRECT_OBJECT
                    previous in PREPOSITIONS -> SyntaxRelation.PREPOSITIONAL_OBJECT
                    index < root && word in SUBJECT_PRONOUNS -> SyntaxRelation.SUBJECT
                    index > root && word in AUXILIARIES -> SyntaxRelation.AUXILIARY
                    index > root -> SyntaxRelation.OBJECT
                    else -> SyntaxRelation.MODIFIER
                }
                arcs += SyntaxArc(root, index, relation, relationConfidence(relation))
            }
        }

        val canonical = arcs
            .distinctBy { Triple(it.headTokenIndex, it.dependentTokenIndex, it.relation) }
            .sortedWith(
                compareBy<SyntaxArc> { it.headTokenIndex }
                    .thenBy { it.dependentTokenIndex }
                    .thenBy { it.relation.name }
            )
        return DependencySyntaxGraph(
            arcs = canonical,
            rootTokenIndices = roots,
            fingerprint = StableCognitiveIds.fingerprint(
                "dependency-syntax/v1",
                *canonical.map {
                    listOf(
                        it.headTokenIndex.toString(),
                        it.dependentTokenIndex.toString(),
                        it.relation.name,
                        java.lang.Double.toHexString(it.confidence),
                    ).joinToString(":")
                }.toTypedArray(),
            ),
        )
    }

    private fun relationConfidence(relation: SyntaxRelation): Double = when (relation) {
        SyntaxRelation.NEGATION -> 0.98
        SyntaxRelation.PARTICLE -> 0.94
        SyntaxRelation.INDIRECT_OBJECT -> 0.90
        SyntaxRelation.SUBJECT -> 0.88
        SyntaxRelation.AUXILIARY -> 0.86
        SyntaxRelation.PREPOSITIONAL_OBJECT -> 0.84
        SyntaxRelation.OBJECT -> 0.80
        SyntaxRelation.MODIFIER -> 0.72
        SyntaxRelation.CLAUSE_MARKER -> 0.72
        SyntaxRelation.ROOT -> 1.0
    }

    private companion object {
        val VERB_FORMS = setOf(
            "mach", "mache", "machen", "erstelle", "erzeuge", "suche", "finde", "recherchiere",
            "schau", "sieh", "guck", "pruf", "pruef", "check", "sende", "schicke", "schick",
            "schreibe", "schreib", "antworte", "plane", "trag", "trage", "merke", "merk",
            "speichere", "baue", "entwickle", "programmiere", "setze", "setz", "zieh",
            "make", "create", "generate", "search", "find", "look", "check", "send", "reply",
            "schedule", "remember", "store", "build", "implement", "develop",
        )
        val SUBJECT_PRONOUNS = setOf(
            "ich", "du", "er", "sie", "es", "wir", "ihr",
            "i", "you", "he", "she", "it", "we", "they",
        )
        val NEGATION = setOf("nicht", "kein", "keine", "nie", "niemals", "not", "never", "no")
        val PARTICLES = setOf(
            "ab", "an", "auf", "aus", "ein", "mit", "nach", "um", "weiter", "zuruck", "zurueck",
        )
        val RECIPIENT_PREPOSITIONS = setOf("an", "fur", "fuer", "to", "for")
        val PREPOSITIONS = RECIPIENT_PREPOSITIONS + setOf(
            "mit", "von", "aus", "bei", "in", "auf", "uber", "ueber", "with", "from", "in", "on",
        )
        val AUXILIARIES = setOf(
            "kann", "konnte", "koennte", "muss", "soll", "darf", "wird", "wurde",
            "can", "could", "must", "should", "may", "will", "would",
        )
    }
}
