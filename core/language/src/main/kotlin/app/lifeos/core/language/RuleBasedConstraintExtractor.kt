package app.lifeos.core.language

class RuleBasedConstraintExtractor {
    private val adjustmentMap = mapOf(
        "wärmer" to "temperature:warm:+", "waermer" to "temperature:warm:+", "warmer" to "temperature:warm:+",
        "kälter" to "temperature:cool:+", "kaelter" to "temperature:cool:+", "cooler" to "temperature:cool:+",
        "heller" to "brightness:+", "brighter" to "brightness:+",
        "dunkler" to "brightness:-", "darker" to "brightness:-",
        "schärfer" to "sharpness:+", "schaerfer" to "sharpness:+", "sharper" to "sharpness:+",
    )
    private val exclusionMarkers = setOf("ohne", "without", "kein", "keine", "keinen", "no")
    private val requirementMarkers = setOf("muss", "müssen", "muessen", "soll", "sollen", "must", "should")
    private val hardRequirements = setOf("offline", "deterministisch", "deterministic", "lokal", "local")
    private val stopWords = setOf("und", "oder", "aber", "and", "or", "but")

    fun extract(utterance: NormalizedUtterance): List<GoalConstraint> {
        val tokens = utterance.tokens
        val constraints = mutableListOf<GoalConstraint>()

        tokens.forEach { token ->
            adjustmentMap[token.normalized]?.let { value ->
                constraints += GoalConstraint("adjustment", value, 0.96, "modifier:${token.original}")
            }
            if (token.normalized in hardRequirements) {
                constraints += GoalConstraint("requirement", token.normalized, 0.88, "keyword:${token.original}")
            }
        }

        for (i in tokens.indices) {
            val marker = tokens[i].normalized
            if (marker in exclusionMarkers) {
                collectPhrase(tokens, i + 1, 4)?.let { phrase ->
                    constraints += GoalConstraint("exclude", phrase, 0.97, "negation:${tokens[i].original}")
                }
            }
            if (marker in requirementMarkers) {
                collectPhrase(tokens, i + 1, 7)?.let { phrase ->
                    constraints += GoalConstraint("require", phrase, 0.91, "modality:${tokens[i].original}")
                }
            }
        }
        return constraints.distinctBy { Triple(it.key, it.value, it.source) }
    }

    private fun collectPhrase(tokens: List<LanguageToken>, start: Int, maxWords: Int): String? {
        if (start >= tokens.size) return null
        val words = mutableListOf<String>()
        var cursor = start
        while (cursor < tokens.size && words.size < maxWords) {
            val token = tokens[cursor]
            if (token.kind == TokenKind.PUNCTUATION) break
            if (token.normalized in stopWords && words.isNotEmpty()) break
            if (token.kind == TokenKind.WORD || token.kind == TokenKind.NUMBER) words += token.normalized
            cursor++
        }
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
