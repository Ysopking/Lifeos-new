package app.lifeos.core.language

class FieldLanguageAdapter {
    fun entities(
        utterance: NormalizedUtterance,
        field: LinguisticFieldResult,
    ): List<SemanticEntity> = field.resolutions.mapNotNull { resolution ->
        val type = resolution.entityType ?: return@mapNotNull null
        val token = utterance.tokens.getOrNull(resolution.tokenIndex) ?: return@mapNotNull null
        SemanticEntity(
            type = type,
            rawText = token.original,
            normalizedValue = resolution.canonical,
            tokenStart = resolution.tokenIndex,
            tokenEndExclusive = resolution.tokenIndex + 1,
            confidence = resolution.confidence,
        )
    }

    fun intentEvidence(field: LinguisticFieldResult): List<IntentEvidence> {
        val imageActivation = field.semanticActivation("IMAGE")
        return field.intentField.mapNotNull { intent ->
            val gate = when (intent.intent) {
                IntentType.CREATE_IMAGE,
                IntentType.TRANSFORM_IMAGE -> if (imageActivation >= 0.52) imageActivation else imageActivation * 0.25
                else -> 1.0
            }
            val score = (intent.activation * gate * FIELD_INTENT_WEIGHT).coerceIn(0.0, 1.0)
            if (score < MIN_INTENT_EVIDENCE) return@mapNotNull null
            IntentEvidence(
                intent = intent.intent,
                score = score,
                reasons = listOf(
                    "linguistic-field:${intent.contributingConcepts.joinToString(",")}",
                    "field-converged=${field.converged}",
                ),
            )
        }
    }

    fun mergeEntities(
        ruleEntities: List<SemanticEntity>,
        fieldEntities: List<SemanticEntity>,
    ): List<SemanticEntity> {
        val merged = linkedMapOf<Triple<EntityType, Int, String>, SemanticEntity>()
        (ruleEntities + fieldEntities).forEach { entity ->
            val key = Triple(entity.type, entity.tokenStart, entity.normalizedValue)
            val existing = merged[key]
            if (existing == null || entity.confidence > existing.confidence) merged[key] = entity
        }
        return merged.values.sortedWith(compareBy<SemanticEntity> { it.tokenStart }.thenBy { it.type.name })
    }

    fun mergeIntentEvidence(
        ruleEvidence: List<IntentEvidence>,
        fieldEvidence: List<IntentEvidence>,
    ): List<IntentEvidence> {
        val intents = (ruleEvidence.map { it.intent } + fieldEvidence.map { it.intent }).distinct()
        return intents.map { intent ->
            val rule = ruleEvidence.firstOrNull { it.intent == intent }
            val field = fieldEvidence.firstOrNull { it.intent == intent }
            val ruleScore = rule?.score ?: 0.0
            val fieldScore = field?.score ?: 0.0
            val combined = (1.0 - (1.0 - ruleScore) * (1.0 - fieldScore)).coerceIn(0.0, 1.0)
            IntentEvidence(
                intent = intent,
                score = combined,
                reasons = (rule?.reasons.orEmpty() + field?.reasons.orEmpty()).distinct(),
            )
        }.sortedWith(compareByDescending<IntentEvidence> { it.score }.thenBy { it.intent.name })
    }

    companion object {
        private const val FIELD_INTENT_WEIGHT = 0.82
        private const val MIN_INTENT_EVIDENCE = 0.18
    }
}
