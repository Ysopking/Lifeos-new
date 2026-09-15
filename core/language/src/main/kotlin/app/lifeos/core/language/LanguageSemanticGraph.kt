package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class SemanticPolarity { POSITIVE, NEGATIVE }

enum class SemanticModality {
    NONE,
    MUST,
    SHOULD,
    MAY,
    CAN,
    WILL,
    WOULD,
    POSSIBLE,
}

enum class SemanticLinkType {
    CONDITION,
    CAUSE,
    PURPOSE,
    CONJUNCTION,
    DISJUNCTION,
    CONTRAST,
    SEQUENCE,
    ATTRIBUTION,
}

data class SemanticQuantity(
    val value: String,
    val unit: String? = null,
    val comparator: String? = null,
    val tokenStart: Int,
    val tokenEndExclusive: Int,
    val confidence: Double = 1.0,
) {
    init {
        require(value.isNotBlank())
        require(unit == null || unit.isNotBlank())
        require(comparator == null || comparator.isNotBlank())
        require(tokenStart >= 0)
        require(tokenEndExclusive > tokenStart)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class SemanticClause(
    val id: Int,
    val text: String,
    val normalized: String,
    val tokenStart: Int,
    val tokenEndExclusive: Int,
    val polarity: SemanticPolarity,
    val modality: SemanticModality,
    val entities: List<SemanticEntity> = emptyList(),
    val quantities: List<SemanticQuantity> = emptyList(),
    val confidence: Double = 1.0,
) {
    init {
        require(id >= 0)
        require(text.isNotBlank())
        require(normalized.isNotBlank())
        require(tokenStart >= 0)
        require(tokenEndExclusive > tokenStart)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class SemanticLink(
    val fromClauseId: Int,
    val toClauseId: Int,
    val type: SemanticLinkType,
    val cue: String,
    val confidence: Double,
) {
    init {
        require(fromClauseId >= 0)
        require(toClauseId >= 0)
        require(fromClauseId != toClauseId)
        require(cue.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

/**
 * Canonical, deterministic semantic representation shared by understanding and generation.
 *
 * The graph deliberately stores clause structure, scoped polarity/modality, quantities and
 * inter-clause relations separately from surface wording. It is intentionally extensible: richer
 * role/coreference/attribution resolvers can add structure later without changing the language
 * round-trip contract.
 */
data class LanguageSemanticGraph(
    val language: LanguageCode,
    val clauses: List<SemanticClause>,
    val links: List<SemanticLink>,
    val fingerprint: String,
) {
    init {
        require(fingerprint.isNotBlank())
        require(clauses.map { it.id }.distinct().size == clauses.size)
        val ids = clauses.mapTo(mutableSetOf()) { it.id }
        require(links.all { it.fromClauseId in ids && it.toClauseId in ids })
    }

    fun featureKeys(): Set<String> = buildSet {
        clauses.forEach { clause ->
            add("polarity:${clause.polarity.name}")
            if (clause.modality != SemanticModality.NONE) add("modality:${clause.modality.name}")
            clause.entities.forEach { entity ->
                add("entity:${entity.type.name}:${normalizeSemanticValue(entity.normalizedValue)}")
            }
            clause.quantities.forEach { quantity ->
                add(
                    "quantity:${quantity.comparator.orEmpty()}:" +
                        "${normalizeSemanticValue(quantity.value)}:${normalizeSemanticValue(quantity.unit.orEmpty())}"
                )
            }
        }
        links.forEach { link -> add("link:${link.type.name}") }
    }

    fun requiresStructuralPreservation(): Boolean =
        links.isNotEmpty() || clauses.any {
            it.polarity == SemanticPolarity.NEGATIVE ||
                it.modality != SemanticModality.NONE ||
                it.quantities.isNotEmpty()
        }

    fun sourceSurface(): String = clauses.joinToString(" ") { it.text.trim() }.trim()

    companion object {
        fun empty(language: LanguageCode = LanguageCode.UNKNOWN): LanguageSemanticGraph = LanguageSemanticGraph(
            language = language,
            clauses = emptyList(),
            links = emptyList(),
            fingerprint = StableCognitiveIds.fingerprint("language-semantic-graph/v1", language.name),
        )
    }
}

/**
 * Deterministic semantic graph extractor. This is not a statistical parser: it establishes the
 * loss-prevention contract needed by LIFEOS so negation, modality, quantities and discourse links
 * cannot silently disappear between understanding and generation.
 */
class LanguageSemanticGraphExtractor {
    fun extract(
        utterance: NormalizedUtterance,
        entities: List<SemanticEntity>,
    ): LanguageSemanticGraph {
        val ranges = clauseRanges(utterance.tokens)
        val clauses = ranges.mapIndexed { id, range ->
            val first = utterance.tokens[range.first]
            val last = utterance.tokens[range.last]
            val text = utterance.original.substring(first.start, last.endExclusive).trim()
            val normalized = utterance.tokens.subList(range.first, range.last + 1)
                .filter { it.kind != TokenKind.PUNCTUATION }
                .joinToString(" ") { it.normalized }
                .trim()
            val scopedEntities = entities.filter { entity ->
                entity.tokenStart < range.last + 1 && entity.tokenEndExclusive > range.first
            }
            SemanticClause(
                id = id,
                text = text,
                normalized = normalized.ifBlank { text.lowercase() },
                tokenStart = range.first,
                tokenEndExclusive = range.last + 1,
                polarity = polarity(utterance.tokens, range),
                modality = modality(utterance.tokens, range),
                entities = scopedEntities,
                quantities = quantities(utterance.tokens, range),
                confidence = clauseConfidence(utterance.tokens, range),
            )
        }
        val links = discourseLinks(clauses, utterance.tokens)
        val fingerprint = StableCognitiveIds.fingerprint(
            "language-semantic-graph/v1",
            utterance.language.name,
            *buildList {
                clauses.forEach { clause ->
                    add("c:${clause.id}:${clause.polarity.name}:${clause.modality.name}")
                    clause.entities.sortedWith(compareBy<SemanticEntity> { it.type.name }.thenBy { it.normalizedValue })
                        .forEach { add("e:${clause.id}:${it.type.name}:${normalizeSemanticValue(it.normalizedValue)}") }
                    clause.quantities.forEach {
                        add("q:${clause.id}:${it.comparator.orEmpty()}:${it.value}:${it.unit.orEmpty()}")
                    }
                }
                links.sortedWith(compareBy<SemanticLink> { it.fromClauseId }.thenBy { it.toClauseId }.thenBy { it.type.name })
                    .forEach { add("l:${it.fromClauseId}:${it.toClauseId}:${it.type.name}") }
            }.toTypedArray(),
        )
        return LanguageSemanticGraph(
            language = utterance.language,
            clauses = clauses,
            links = links,
            fingerprint = fingerprint,
        )
    }

    private fun clauseRanges(tokens: List<LanguageToken>): List<IntRange> {
        if (tokens.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var start = 0
        tokens.forEachIndexed { index, token ->
            val punctuationBoundary = token.kind == TokenKind.PUNCTUATION && token.original in CLAUSE_PUNCTUATION
            val connectiveBoundary = index > start && token.kind == TokenKind.WORD && token.normalized in RELATION_CUES
            if (connectiveBoundary) {
                val end = previousContent(tokens, index - 1, start)
                if (end >= start) result += start..end
                start = index
            }
            if (punctuationBoundary) {
                val end = previousContent(tokens, index - 1, start)
                if (end >= start) result += start..end
                start = index + 1
            }
        }
        val finalStart = nextContent(tokens, start)
        if (finalStart < tokens.size) {
            val end = previousContent(tokens, tokens.lastIndex, finalStart)
            if (end >= finalStart) result += finalStart..end
        }
        return result.ifEmpty {
            val startContent = nextContent(tokens, 0)
            val endContent = previousContent(tokens, tokens.lastIndex, startContent)
            if (startContent <= endContent) listOf(startContent..endContent) else emptyList()
        }
    }

    private fun polarity(tokens: List<LanguageToken>, range: IntRange): SemanticPolarity =
        if (range.any { tokens[it].normalized in NEGATION_MARKERS }) SemanticPolarity.NEGATIVE
        else SemanticPolarity.POSITIVE

    private fun modality(tokens: List<LanguageToken>, range: IntRange): SemanticModality {
        range.forEach { index -> MODALITY_MARKERS[tokens[index].normalized]?.let { return it } }
        return SemanticModality.NONE
    }

    private fun quantities(tokens: List<LanguageToken>, range: IntRange): List<SemanticQuantity> = buildList {
        range.forEach { index ->
            val token = tokens[index]
            if (token.kind != TokenKind.NUMBER) return@forEach
            val previous = tokens.getOrNull(index - 1)?.normalized
            val unitToken = tokens.getOrNull(index + 1)
                ?.takeIf { it.kind == TokenKind.WORD && it.normalized !in RELATION_CUES && it.normalized !in MODALITY_MARKERS }
            add(
                SemanticQuantity(
                    value = token.normalized,
                    unit = unitToken?.normalized,
                    comparator = previous?.let(COMPARATOR_MARKERS::get),
                    tokenStart = index,
                    tokenEndExclusive = if (unitToken != null) index + 2 else index + 1,
                    confidence = if (unitToken == null) 0.92 else 0.97,
                )
            )
        }
    }

    private fun discourseLinks(
        clauses: List<SemanticClause>,
        tokens: List<LanguageToken>,
    ): List<SemanticLink> = buildList {
        if (clauses.size < 2) return@buildList
        clauses.forEachIndexed { index, clause ->
            val cueIndex = (clause.tokenStart until clause.tokenEndExclusive).firstOrNull {
                tokens[it].normalized in RELATION_CUES
            } ?: return@forEachIndexed
            val cue = tokens[cueIndex].normalized
            val type = RELATION_CUES.getValue(cue)
            when (type) {
                SemanticLinkType.CONDITION -> if (index + 1 < clauses.size) {
                    add(SemanticLink(clause.id, clauses[index + 1].id, type, cue, 0.94))
                }
                SemanticLinkType.CAUSE,
                SemanticLinkType.PURPOSE,
                SemanticLinkType.CONJUNCTION,
                SemanticLinkType.DISJUNCTION,
                SemanticLinkType.CONTRAST,
                SemanticLinkType.SEQUENCE,
                SemanticLinkType.ATTRIBUTION -> if (index > 0) {
                    add(SemanticLink(clauses[index - 1].id, clause.id, type, cue, 0.92))
                }
            }
        }
    }.distinctBy { Triple(it.fromClauseId, it.toClauseId, it.type) }

    private fun clauseConfidence(tokens: List<LanguageToken>, range: IntRange): Double {
        val content = range.count { tokens[it].kind != TokenKind.PUNCTUATION }
        return when {
            content >= 3 -> 0.96
            content == 2 -> 0.90
            else -> 0.82
        }
    }

    private fun nextContent(tokens: List<LanguageToken>, from: Int): Int {
        var index = from.coerceAtLeast(0)
        while (index < tokens.size && tokens[index].kind == TokenKind.PUNCTUATION) index++
        return index
    }

    private fun previousContent(tokens: List<LanguageToken>, from: Int, minimum: Int): Int {
        var index = from.coerceAtMost(tokens.lastIndex)
        while (index >= minimum && tokens[index].kind == TokenKind.PUNCTUATION) index--
        return index
    }

    private companion object {
        val CLAUSE_PUNCTUATION = setOf(",", ";", ".", "!", "?")
        val NEGATION_MARKERS = setOf(
            "nicht", "kein", "keine", "keinen", "keinem", "keiner", "niemals", "nie", "ohne",
            "not", "no", "never", "without", "cannot", "can't", "won't", "isn't", "aren't",
        )
        val MODALITY_MARKERS = mapOf(
            "muss" to SemanticModality.MUST, "muessen" to SemanticModality.MUST, "müssen" to SemanticModality.MUST,
            "must" to SemanticModality.MUST, "soll" to SemanticModality.SHOULD, "sollen" to SemanticModality.SHOULD,
            "should" to SemanticModality.SHOULD, "darf" to SemanticModality.MAY, "dürfen" to SemanticModality.MAY,
            "duerfen" to SemanticModality.MAY, "may" to SemanticModality.MAY, "kann" to SemanticModality.CAN,
            "können" to SemanticModality.CAN, "koennen" to SemanticModality.CAN, "can" to SemanticModality.CAN,
            "wird" to SemanticModality.WILL, "werden" to SemanticModality.WILL, "will" to SemanticModality.WILL,
            "würde" to SemanticModality.WOULD, "wuerde" to SemanticModality.WOULD, "would" to SemanticModality.WOULD,
            "möglich" to SemanticModality.POSSIBLE, "moeglich" to SemanticModality.POSSIBLE,
            "possible" to SemanticModality.POSSIBLE,
        )
        val RELATION_CUES = mapOf(
            "wenn" to SemanticLinkType.CONDITION, "falls" to SemanticLinkType.CONDITION,
            "sofern" to SemanticLinkType.CONDITION, "if" to SemanticLinkType.CONDITION,
            "unless" to SemanticLinkType.CONDITION,
            "weil" to SemanticLinkType.CAUSE, "da" to SemanticLinkType.CAUSE,
            "because" to SemanticLinkType.CAUSE, "since" to SemanticLinkType.CAUSE,
            "damit" to SemanticLinkType.PURPOSE, "sodass" to SemanticLinkType.PURPOSE,
            "so" to SemanticLinkType.PURPOSE,
            "und" to SemanticLinkType.CONJUNCTION, "and" to SemanticLinkType.CONJUNCTION,
            "oder" to SemanticLinkType.DISJUNCTION, "or" to SemanticLinkType.DISJUNCTION,
            "aber" to SemanticLinkType.CONTRAST, "jedoch" to SemanticLinkType.CONTRAST,
            "but" to SemanticLinkType.CONTRAST, "however" to SemanticLinkType.CONTRAST,
            "danach" to SemanticLinkType.SEQUENCE, "anschliessend" to SemanticLinkType.SEQUENCE,
            "anschließend" to SemanticLinkType.SEQUENCE, "then" to SemanticLinkType.SEQUENCE,
            "laut" to SemanticLinkType.ATTRIBUTION, "according" to SemanticLinkType.ATTRIBUTION,
        )
        val COMPARATOR_MARKERS = mapOf(
            "mindestens" to ">=", "minimum" to ">=", "at-least" to ">=",
            "höchstens" to "<=", "hoechstens" to "<=", "maximum" to "<=", "at-most" to "<=",
            "über" to ">", "ueber" to ">", "above" to ">", "more" to ">",
            "unter" to "<", "below" to "<", "less" to "<",
        )
    }
}

fun semanticGraphCoverage(expected: LanguageSemanticGraph, actual: LanguageSemanticGraph): Double {
    val wanted = expected.featureKeys()
    if (wanted.isEmpty()) return 1.0
    val found = actual.featureKeys()
    return wanted.count(found::contains).toDouble() / wanted.size.toDouble()
}

private fun normalizeSemanticValue(value: String): String = value
    .lowercase()
    .replace("ß", "ss")
    .replace(Regex("\\s+"), " ")
    .trim()
