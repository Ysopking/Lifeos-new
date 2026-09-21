package app.lifeos.core.language

data class SemanticContextQuery(
    val terms: Set<String>,
    val concepts: Set<String>,
    val semanticTypes: Set<String>,
    val preferredKinds: Set<String>,
)

class SemanticContextQueryPlanner(
    private val lexicon: LinguisticLexicon = DeterministicLinguisticFieldLexicon(),
    private val normalizer: UtteranceNormalizer = UtteranceNormalizer(),
) {
    fun plan(text: String): SemanticContextQuery {
        val utterance = normalizer.normalize(text)
        val tokens = utterance.tokens
            .filter { it.kind == TokenKind.WORD || it.kind == TokenKind.NUMBER }
            .map { normalizeFieldText(it.normalized) }
            .filter { it.isNotBlank() }
            .toSet()
        val matchedConcepts = lexicon.concepts.filter { concept ->
            concept.allForms.any { it in tokens }
        }
        val concepts = matchedConcepts.mapTo(linkedSetOf()) { it.id }
        val semanticTypes = matchedConcepts
            .mapTo(linkedSetOf()) { normalizeFieldText(it.semanticTag) }
        val kinds = buildSet {
            if (matchedConcepts.any { it.semanticTag == "IMAGE" }) add("image")
            if (matchedConcepts.any { it.semanticTag in setOf("FILE", "DOCUMENT", "NOTICE") }) add("file")
            if (matchedConcepts.any { it.intentBias.containsKey(IntentType.CONTINUE) }) add("goal")
            if (matchedConcepts.any { it.semanticTag == "RESULT" }) add("result")
        }
        return SemanticContextQuery(tokens, concepts, semanticTypes, kinds)
    }
}
