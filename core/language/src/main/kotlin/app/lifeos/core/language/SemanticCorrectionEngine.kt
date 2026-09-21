package app.lifeos.core.language

data class SemanticCorrection(
    val tokenIndex: Int,
    val original: String,
    val canonical: String,
    val confidence: Double,
    val semanticTag: String,
) {
    init {
        require(tokenIndex >= 0)
        require(original.isNotBlank())
        require(canonical.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(semanticTag.isNotBlank())
    }
}

class SemanticCorrectionEngine(
    private val minimumConfidence: Double = 0.84,
) {
    init { require(minimumConfidence in 0.0..1.0) }

    fun project(
        utterance: NormalizedUtterance,
        field: LinguisticFieldResult,
    ): List<SemanticCorrection> = field.resolutions.mapNotNull { resolution ->
        val token = utterance.tokens.getOrNull(resolution.tokenIndex) ?: return@mapNotNull null
        if (resolution.confidence < minimumConfidence) return@mapNotNull null
        if (normalizeFieldText(token.normalized) == normalizeFieldText(resolution.canonical)) {
            return@mapNotNull null
        }
        SemanticCorrection(
            tokenIndex = resolution.tokenIndex,
            original = token.original,
            canonical = resolution.canonical,
            confidence = resolution.confidence,
            semanticTag = resolution.semanticTag,
        )
    }.distinctBy { it.tokenIndex to it.canonical }
}
