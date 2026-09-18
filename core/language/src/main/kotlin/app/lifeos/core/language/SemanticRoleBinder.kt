package app.lifeos.core.language

/**
 * Generic deterministic role binder shared by predicate parsing.
 *
 * It binds only explicit clause-local entities/quantities and already-resolved references.
 * Predicate-specific syntactic roles (for example debtor/creditor/recipient direction) remain
 * layered in [PredicateFrameParser] and never get invented from intent metadata.
 */
class SemanticRoleBinder {
    fun bindBase(
        clause: SemanticClause,
        contract: PredicateActionContract,
        references: List<ResolvedReference>,
    ): MutableMap<SemanticRole, SemanticValue> {
        val roles = linkedMapOf<SemanticRole, SemanticValue>()

        clause.entities
            .sortedBy { it.tokenStart }
            .forEach { entity ->
                when (entity.type) {
                    EntityType.IMAGE -> roles.putIfAbsent(SemanticRole.OBJECT, entity.asValue())
                    EntityType.FILE,
                    EntityType.OBJECT -> roles.putIfAbsent(SemanticRole.OBJECT, entity.asValue())
                    EntityType.LOCATION -> roles.putIfAbsent(SemanticRole.LOCATION, entity.asValue())
                    EntityType.DATE -> roles.putIfAbsent(SemanticRole.DATE, entity.asValue())
                    EntityType.TIME -> roles.putIfAbsent(SemanticRole.TIME, entity.asValue())
                    EntityType.DURATION -> roles.putIfAbsent(SemanticRole.DURATION, entity.asValue())
                    else -> Unit
                }
            }

        clause.quantities
            .sortedBy { it.tokenStart }
            .firstOrNull()
            ?.let { quantity ->
                roles[SemanticRole.AMOUNT] = SemanticValue(
                    rawText = quantity.value + quantity.unit?.let { " " + it }.orEmpty(),
                    normalized = quantity.value,
                    quantity = quantity,
                    confidence = quantity.confidence,
                )
                quantity.unit?.let { unit ->
                    roles[SemanticRole.UNIT] = SemanticValue(
                        rawText = unit,
                        normalized = unit,
                        confidence = quantity.confidence,
                    )
                    normalizeCurrency(unit)?.let { currency ->
                        roles[SemanticRole.CURRENCY] = SemanticValue(
                            rawText = unit,
                            normalized = currency,
                            confidence = quantity.confidence,
                        )
                    }
                }
            }

        val referenceRole = contract.referenceRole
        if (referenceRole != null) {
            references.asSequence()
                .filter { it.targetPhotonRef != null }
                .maxByOrNull { it.score }
                ?.let { resolvedReference ->
                    val ref = requireNotNull(resolvedReference.targetPhotonRef)
                    roles[referenceRole] = SemanticValue(
                        rawText = resolvedReference.expression.rawText,
                        normalized = ref.photonId.value,
                        referencePhoton = ref,
                        resolved = true,
                        confidence = resolvedReference.score,
                    )
                }
        }

        return roles
    }

    private fun SemanticEntity.asValue(): SemanticValue = SemanticValue(
        rawText = rawText,
        normalized = normalizedValue,
        entityType = type,
        confidence = confidence,
    )

    private fun normalizeCurrency(unit: String): String? = when (unit.lowercase()) {
        "€", "eur", "euro", "euros" -> "EUR"
        "$", "usd", "dollar" -> "USD"
        "£", "gbp", "pound", "pounds" -> "GBP"
        else -> null
    }
}
