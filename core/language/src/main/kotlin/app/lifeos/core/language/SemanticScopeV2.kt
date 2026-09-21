package app.lifeos.core.language

enum class SemanticOperatorType {
    ONLY,
    EXCEPT,
    CORRECTION,
}

data class SemanticOperatorScope(
    val type: SemanticOperatorType,
    val cue: String,
    val span: TextSpan,
    val confidence: Double,
) {
    init {
        require(cue.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

class ScopeCueDetectorV2 {
    fun detect(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
    ): List<SemanticOperatorScope> {
        val result = mutableListOf<SemanticOperatorScope>()
        for (index in clause.tokenStart until clause.tokenEndExclusive) {
            val token = utterance.tokens[index]
            val type = when (token.normalized) {
                "nur", "only" -> SemanticOperatorType.ONLY
                "ausser", "außer", "except" -> SemanticOperatorType.EXCEPT
                "sondern", "stattdessen", "rather", "instead" -> SemanticOperatorType.CORRECTION
                else -> null
            } ?: continue
            result += SemanticOperatorScope(
                type = type,
                cue = token.original,
                span = TextSpan(token.start, token.endExclusive),
                confidence = 0.96,
            )
        }
        return result.distinctBy { Triple(it.type, it.span.start, it.span.endExclusive) }
    }
}
