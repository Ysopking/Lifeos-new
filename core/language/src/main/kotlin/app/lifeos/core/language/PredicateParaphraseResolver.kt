package app.lifeos.core.language

data class PredicateParaphraseMatch(
    val predicate: PredicateConcept,
    val localTokenIndex: Int,
    val confidence: Double,
    val source: String,
    val detail: String,
) {
    init {
        require(localTokenIndex >= 0)
        require(confidence in 0.0..1.0)
        require(source.isNotBlank())
        require(detail.isNotBlank())
    }
}

class PredicateParaphraseResolver {
    private data class Pattern(
        val terms: List<String>,
        val predicate: PredicateConcept,
        val confidence: Double,
    )

    fun resolve(tokens: List<LanguageToken>): List<PredicateParaphraseMatch> {
        val words = tokens.withIndex().filter { it.value.kind == TokenKind.WORD }
        if (words.isEmpty()) return emptyList()
        val normalized = words.map { it.value.normalized }
        val out = mutableListOf<PredicateParaphraseMatch>()
        PATTERNS.forEach { pattern ->
            if (pattern.terms.size > normalized.size) return@forEach
            for (start in 0..normalized.size - pattern.terms.size) {
                val window = normalized.subList(start, start + pattern.terms.size)
                if (window != pattern.terms) continue
                out += PredicateParaphraseMatch(
                    predicate = pattern.predicate,
                    localTokenIndex = words[start].index,
                    confidence = safeConfidence(pattern.predicate, pattern.confidence),
                    source = "predicate-paraphrase/v1",
                    detail = "phrase=" + pattern.terms.joinToString(" "),
                )
            }
        }
        return out
            .groupBy { it.predicate to it.localTokenIndex }
            .map { (_, matches) -> matches.maxBy { it.confidence } }
            .sortedWith(compareBy<PredicateParaphraseMatch> { it.localTokenIndex }.thenBy { it.predicate.name })
    }

    fun fromField(
        field: LinguisticFieldResult?,
        clauseTokenStart: Int,
        clauseTokenEndExclusive: Int,
    ): List<PredicateParaphraseMatch> {
        if (field == null) return emptyList()
        return field.resolutions.mapNotNull { resolution ->
            if (resolution.tokenIndex !in clauseTokenStart until clauseTokenEndExclusive) {
                return@mapNotNull null
            }
            val predicate = FIELD_TAG_TO_PREDICATE[resolution.semanticTag]
                ?: return@mapNotNull null
            if (resolution.confidence < FIELD_MIN_CONFIDENCE) return@mapNotNull null
            PredicateParaphraseMatch(
                predicate = predicate,
                localTokenIndex = resolution.tokenIndex - clauseTokenStart,
                confidence = safeConfidence(predicate, resolution.confidence * FIELD_CONFIDENCE_SCALE),
                source = "linguistic-field-predicate/v1",
                detail = "tag=" + resolution.semanticTag +
                    ";canonical=" + resolution.canonical +
                    ";confidence=" + resolution.confidence,
            )
        }
    }

    private fun safeConfidence(predicate: PredicateConcept, raw: Double): Double {
        val cap = if (predicate in SIDE_EFFECT_PREDICATES) {
            SIDE_EFFECT_UNDERSTANDING_CAP
        } else {
            LOCAL_UNDERSTANDING_CAP
        }
        return raw.coerceIn(0.0, cap)
    }

    private companion object {
        const val FIELD_MIN_CONFIDENCE = 0.72
        const val FIELD_CONFIDENCE_SCALE = 0.96
        const val SIDE_EFFECT_UNDERSTANDING_CAP = 0.74
        const val LOCAL_UNDERSTANDING_CAP = 0.90

        val SIDE_EFFECT_PREDICATES = setOf(
            PredicateConcept.COMMUNICATE,
            PredicateConcept.SCHEDULE,
            PredicateConcept.STORE_MEMORY,
            PredicateConcept.PAY,
            PredicateConcept.DELETE,
            PredicateConcept.UPLOAD,
        )

        val FIELD_TAG_TO_PREDICATE = mapOf(
            "CREATE" to PredicateConcept.CREATE_IMAGE,
            "TRANSFORM" to PredicateConcept.TRANSFORM_IMAGE,
            "SEARCH" to PredicateConcept.SEARCH,
            "CONTINUE" to PredicateConcept.CONTINUE,
            "IMPLEMENT" to PredicateConcept.BUILD,
            "COMMUNICATE" to PredicateConcept.COMMUNICATE,
            "SCHEDULE" to PredicateConcept.SCHEDULE,
            "STORE_MEMORY" to PredicateConcept.STORE_MEMORY,
        )

        val PATTERNS = listOf(
            Pattern(listOf("schau", "nach"), PredicateConcept.SEARCH, 0.88),
            Pattern(listOf("sieh", "nach"), PredicateConcept.SEARCH, 0.88),
            Pattern(listOf("guck", "nach"), PredicateConcept.SEARCH, 0.84),
            Pattern(listOf("find", "heraus"), PredicateConcept.SEARCH, 0.88),
            Pattern(listOf("finde", "heraus"), PredicateConcept.SEARCH, 0.90),
            Pattern(listOf("look", "up"), PredicateConcept.SEARCH, 0.90),
            Pattern(listOf("find", "out"), PredicateConcept.SEARCH, 0.88),
            Pattern(listOf("gib", "bescheid"), PredicateConcept.COMMUNICATE, 0.90),
            Pattern(listOf("sag", "bescheid"), PredicateConcept.COMMUNICATE, 0.88),
            Pattern(listOf("lass", "wissen"), PredicateConcept.COMMUNICATE, 0.88),
            Pattern(listOf("let", "know"), PredicateConcept.COMMUNICATE, 0.88),
            Pattern(listOf("trag", "ein"), PredicateConcept.SCHEDULE, 0.88),
            Pattern(listOf("trage", "ein"), PredicateConcept.SCHEDULE, 0.90),
            Pattern(listOf("merk", "dir"), PredicateConcept.STORE_MEMORY, 0.90),
            Pattern(listOf("merke", "dir"), PredicateConcept.STORE_MEMORY, 0.92),
            Pattern(listOf("behalt", "im", "kopf"), PredicateConcept.STORE_MEMORY, 0.86),
            Pattern(listOf("behalte", "im", "kopf"), PredicateConcept.STORE_MEMORY, 0.88),
            Pattern(listOf("mach", "weiter"), PredicateConcept.CONTINUE, 0.94),
            Pattern(listOf("mache", "weiter"), PredicateConcept.CONTINUE, 0.94),
            Pattern(listOf("zieh", "durch"), PredicateConcept.CONTINUE, 0.88),
            Pattern(listOf("setze", "um"), PredicateConcept.BUILD, 0.90),
            Pattern(listOf("setz", "um"), PredicateConcept.BUILD, 0.88),
            Pattern(listOf("mach", "fertig"), PredicateConcept.BUILD, 0.84),
            Pattern(listOf("get", "working"), PredicateConcept.BUILD, 0.82),
        )
    }
}
