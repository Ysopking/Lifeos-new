package app.lifeos.core.language

enum class LinguisticFieldLayer {
    GRAPHEME,
    LEXEME,
    SEMANTIC,
    INTENT,
    CONTEXT,
}

data class LinguisticFieldWeights(
    val grapheme: Double = 0.34,
    val morphology: Double = 0.20,
    val lexical: Double = 0.18,
    val sentenceContext: Double = 0.16,
    val photonContext: Double = 0.08,
    val intentCoherence: Double = 0.08,
    val competitionRepulsion: Double = 0.12,
) {
    init {
        require(listOf(grapheme, morphology, lexical, sentenceContext, photonContext, intentCoherence, competitionRepulsion).all { it >= 0.0 && it.isFinite() })
    }
}

data class LinguisticConcept(
    val id: String,
    val canonical: String,
    val variants: Set<String>,
    val semanticTag: String,
    val entityType: EntityType? = null,
    val intentBias: Map<IntentType, Double> = emptyMap(),
    val attractsTags: Set<String> = emptySet(),
    val repelsTags: Set<String> = emptySet(),
    val semanticMass: Double = 1.0,
) {
    init {
        require(id.isNotBlank())
        require(canonical.isNotBlank())
        require(semanticTag.isNotBlank())
        require(semanticMass.isFinite() && semanticMass > 0.0)
        require(intentBias.values.all { it in 0.0..1.0 })
    }

    val allForms: Set<String> = (variants + canonical).map(::normalizeFieldText).toSet()
}

data class LinguisticFieldCandidate(
    val tokenIndex: Int,
    val token: String,
    val conceptId: String,
    val canonical: String,
    val semanticTag: String,
    val entityType: EntityType?,
    val activation: Double,
    val graphemeAffinity: Double,
    val morphologyAffinity: Double,
    val sentenceAttraction: Double,
    val photonAttraction: Double,
    val intentAttraction: Double,
    val repulsion: Double,
) {
    init {
        require(tokenIndex >= 0)
        require(token.isNotBlank())
        require(conceptId.isNotBlank())
        require(activation in 0.0..1.0)
    }
}

data class LinguisticFieldInteraction(
    val sourceConceptId: String,
    val targetConceptId: String,
    val force: Double,
    val reason: String,
) {
    init {
        require(sourceConceptId.isNotBlank())
        require(targetConceptId.isNotBlank())
        require(force.isFinite())
        require(reason.isNotBlank())
    }
}

data class LinguisticFieldResolution(
    val tokenIndex: Int,
    val rawToken: String,
    val canonical: String,
    val semanticTag: String,
    val entityType: EntityType?,
    val confidence: Double,
    val alternatives: List<Pair<String, Double>>,
) {
    init {
        require(tokenIndex >= 0)
        require(rawToken.isNotBlank())
        require(canonical.isNotBlank())
        require(semanticTag.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class LinguisticIntentField(
    val intent: IntentType,
    val activation: Double,
    val contributingConcepts: List<String>,
) {
    init { require(activation in 0.0..1.0) }
}

data class LinguisticFieldResult(
    val resolutions: List<LinguisticFieldResolution>,
    val intentField: List<LinguisticIntentField>,
    val interactions: List<LinguisticFieldInteraction>,
    val converged: Boolean,
    val iterations: Int,
    val totalEnergy: Double,
) {
    init {
        require(iterations >= 0)
        require(totalEnergy.isFinite())
    }

    fun semanticActivation(tag: String): Double = resolutions
        .filter { it.semanticTag == tag }
        .maxOfOrNull { it.confidence }
        ?: 0.0
}

internal fun normalizeFieldText(value: String): String = value
    .lowercase()
    .replace('ä', 'a')
    .replace('ö', 'o')
    .replace('ü', 'u')
    .replace('ß', 's')
    .replace(Regex("[^a-z0-9]+"), "")
