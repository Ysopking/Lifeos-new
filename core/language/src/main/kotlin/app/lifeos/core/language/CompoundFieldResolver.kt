package app.lifeos.core.language

/** A semantic component bound inside one orthographic token (e.g. Fussball + Flutlicht). */
data class CompoundFieldComponent(
    val conceptId: String,
    val canonical: String,
    val semanticTag: String,
    val entityType: EntityType?,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val confidence: Double,
) {
    init {
        require(conceptId.isNotBlank())
        require(canonical.isNotBlank())
        require(semanticTag.isNotBlank())
        require(startOffset >= 0)
        require(endOffsetExclusive > startOffset)
        require(confidence in 0.0..1.0)
    }
}

data class CompoundFieldBinding(
    val tokenIndex: Int,
    val rawToken: String,
    val normalizedToken: String,
    val components: List<CompoundFieldComponent>,
    val linkerRanges: List<IntRange>,
    val confidence: Double,
) {
    init {
        require(tokenIndex >= 0)
        require(rawToken.isNotBlank())
        require(normalizedToken.isNotBlank())
        require(components.size >= 2)
        require(confidence in 0.0..1.0)
    }
}

/**
 * Deterministic German/English compound segmentation over the semantic lexicon.
 * It does not invent missing concepts: every accepted segment must bind to a known field concept.
 */
class CompoundFieldResolver(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val lexicalIndex: LinguisticFieldIndexV2 = LinguisticFieldIndexV2(lexicon),
) {
    fun resolve(utterance: NormalizedUtterance): List<CompoundFieldBinding> = utterance.tokens
        .withIndex()
        .filter { it.value.kind == TokenKind.WORD }
        .mapNotNull { indexed -> resolveToken(indexed.index, indexed.value.original) }

    fun resolveToken(tokenIndex: Int, rawToken: String): CompoundFieldBinding? {
        val token = normalizeFieldText(rawToken)
        if (token.length < MIN_COMPOUND_LENGTH) return null
        var best: Candidate? = null
        fun search(position: Int, components: List<CompoundFieldComponent>, linkers: List<IntRange>) {
            if (position == token.length) {
                if (components.size >= 2) {
                    val candidate = Candidate(components, linkers, score(token, components, linkers))
                    if (best == null || candidate.score > best!!.score) best = candidate
                }
                return
            }
            if (components.size >= MAX_COMPONENTS) return

            lexicalIndex.formsStartingAt(token, position)
                .filter { it.form.length >= MIN_COMPONENT_LENGTH }
                .forEach { indexed ->
                val concept = lexicon.byId(indexed.conceptId) ?: return@forEach
                val end = position + indexed.form.length
                val component = CompoundFieldComponent(
                    conceptId = concept.id,
                    canonical = concept.canonical,
                    semanticTag = concept.semanticTag,
                    entityType = concept.entityType,
                    startOffset = position,
                    endOffsetExclusive = end,
                    confidence = 0.94,
                )
                search(end, components + component, linkers)
                LINKERS.forEach { linker ->
                    if (end < token.length && token.startsWith(linker, end)) {
                        val linkerEnd = end + linker.length
                        if (linkerEnd < token.length) {
                            search(
                                linkerEnd,
                                components + component,
                                linkers + listOf(end until linkerEnd),
                            )
                        }
                    }
                }
            }
        }
        search(0, emptyList(), emptyList())
        val chosen = best ?: return null
        val confidence = chosen.score.coerceIn(0.0, 1.0)
        if (confidence < MIN_BINDING_CONFIDENCE) return null
        return CompoundFieldBinding(
            tokenIndex = tokenIndex,
            rawToken = rawToken,
            normalizedToken = token,
            components = chosen.components.map { it.copy(confidence = confidence) },
            linkerRanges = chosen.linkers,
            confidence = confidence,
        )
    }

    private fun score(
        token: String,
        components: List<CompoundFieldComponent>,
        linkers: List<IntRange>,
    ): Double {
        val semanticMass = components.mapNotNull { component -> lexicon.byId(component.conceptId)?.semanticMass }.average()
        val semanticDiversity = components.map { it.semanticTag }.distinct().size.toDouble() / components.size.toDouble()
        val linkerChars = linkers.sumOf { it.last - it.first + 1 }
        val lexicalCoverage = (token.length - linkerChars).toDouble() / token.length.toDouble()
        return (
            lexicalCoverage * 0.58 +
                semanticDiversity * 0.18 +
                (semanticMass / 1.5).coerceIn(0.0, 1.0) * 0.16 +
                (components.size.coerceAtMost(3) / 3.0) * 0.08
            ).coerceIn(0.0, 1.0)
    }

    private data class Candidate(
        val components: List<CompoundFieldComponent>,
        val linkers: List<IntRange>,
        val score: Double,
    )

    companion object {
        private const val MIN_COMPONENT_LENGTH = 3
        private const val MIN_COMPOUND_LENGTH = 7
        private const val MAX_COMPONENTS = 4
        private const val MIN_BINDING_CONFIDENCE = 0.66
        private val LINKERS = listOf("s", "es", "en", "er", "e", "n")
    }
}
