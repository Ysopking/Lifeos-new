package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class DomainSemanticPackId {
    AUTHORITY,
    DEBT,
    FINANCE,
    APPOINTMENT,
    COMMUNICATION,
    CONTRACT,
    HEALTH,
    DOCUMENTS,
}

@JvmInline
value class DomainSemanticNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    companion object {
        const val PREFIX = "domain-semantic-node:"
        fun create(vararg parts: String): DomainSemanticNodeId =
            DomainSemanticNodeId(
                PREFIX + StableCognitiveIds.fingerprint("domain-semantic-node/v1", *parts)
            )
    }
}

data class DomainSemanticNode(
    val id: DomainSemanticNodeId,
    val pack: DomainSemanticPackId,
    val type: String,
    val value: String,
    val confidence: Double,
    val sourceEntityType: SemanticEntityTypeId? = null,
) {
    init {
        require(type.isNotBlank())
        require(value.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

enum class DomainSemanticRelationType {
    ISSUED,
    CONTAINS,
    HAS_AMOUNT,
    HAS_DEADLINE,
    OWES_TO,
    PAYS_TO,
    HAS_RECIPIENT,
    HAS_DOCUMENT,
    HAS_DURATION,
    HAS_DOSAGE,
    RELATES_TO,
}

data class DomainSemanticRelation(
    val from: DomainSemanticNodeId,
    val to: DomainSemanticNodeId,
    val type: DomainSemanticRelationType,
    val confidence: Double,
) {
    init {
        require(from != to)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class DomainSemanticGraph(
    val packs: Set<DomainSemanticPackId>,
    val nodes: List<DomainSemanticNode>,
    val relations: List<DomainSemanticRelation>,
    val fingerprint: String,
) {
    init {
        val ids = nodes.mapTo(linkedSetOf()) { it.id }
        require(relations.all { it.from in ids && it.to in ids })
        require(fingerprint.isNotBlank())
    }

    companion object {
        fun empty(): DomainSemanticGraph = DomainSemanticGraph(
            packs = emptySet(),
            nodes = emptyList(),
            relations = emptyList(),
            fingerprint = StableCognitiveIds.fingerprint("domain-semantic-graph/v1", "empty"),
        )
    }
}

class DomainSemanticInterpreter {
    fun interpret(
        utterance: NormalizedUtterance,
        entities: List<SemanticEntityV2>,
        quantityTemporal: QuantityTemporalResult,
        actionGraph: SemanticActionGraph,
    ): DomainSemanticGraph {
        val nodes = mutableListOf<DomainSemanticNode>()
        val relations = mutableListOf<DomainSemanticRelation>()

        fun addNode(
            pack: DomainSemanticPackId,
            type: String,
            value: String,
            confidence: Double,
            sourceType: SemanticEntityTypeId? = null,
        ): DomainSemanticNode {
            val node = DomainSemanticNode(
                id = DomainSemanticNodeId.create(
                    pack.name,
                    type,
                    value,
                    sourceType?.value.orEmpty(),
                ),
                pack = pack,
                type = type,
                value = value,
                confidence = confidence,
                sourceEntityType = sourceType,
            )
            nodes += node
            return node
        }

        val entityNodes = entities.associateWith { entity ->
            val pack = packFor(entity.typeId)
            addNode(
                pack = pack,
                type = entity.typeId.value,
                value = entity.normalizedValue,
                confidence = entity.confidence,
                sourceType = entity.typeId,
            )
        }

        val quantityNodes = quantityTemporal.quantities.mapIndexed { index, quantity ->
            addNode(
                pack = if (quantity.currency != null) DomainSemanticPackId.FINANCE else DomainSemanticPackId.DOCUMENTS,
                type = if (quantity.currency != null) "finance.amount" else "quantity.value",
                value = listOf(
                    quantity.comparator.name,
                    quantity.value?.toPlainString().orEmpty(),
                    quantity.lowerBound?.toPlainString().orEmpty(),
                    quantity.upperBound?.toPlainString().orEmpty(),
                    quantity.currency?.currencyCode ?: quantity.unit.orEmpty(),
                ).joinToString(":"),
                confidence = quantity.confidence,
            )
        }

        val temporalNodes = quantityTemporal.temporals.map { temporal ->
            addNode(
                pack = DomainSemanticPackId.APPOINTMENT,
                type = "temporal." + temporal.relation.name.lowercase(),
                value = listOf(
                    temporal.startInclusive?.toString().orEmpty(),
                    temporal.endInclusive?.toString().orEmpty(),
                ).joinToString(":"),
                confidence = temporal.confidence,
            )
        }

        val authority = entityNodes.entries
            .firstOrNull { it.key.typeId == EntityTypeRegistry.AUTHORITY.id }
            ?.value
        val notice = entityNodes.entries
            .firstOrNull { it.key.typeId == EntityTypeRegistry.NOTICE.id }
            ?.value
        val claimOrDebt = entityNodes.entries
            .firstOrNull {
                it.key.typeId in setOf(EntityTypeRegistry.CLAIM.id, EntityTypeRegistry.DEBT.id)
            }
            ?.value
        val deadline = entityNodes.entries
            .firstOrNull { it.key.typeId == EntityTypeRegistry.DEADLINE.id }
            ?.value ?: temporalNodes.firstOrNull()

        if (authority != null && notice != null) {
            relations += relation(authority, notice, DomainSemanticRelationType.ISSUED)
        }
        if (notice != null && claimOrDebt != null) {
            relations += relation(notice, claimOrDebt, DomainSemanticRelationType.CONTAINS)
        }
        val amountTarget = claimOrDebt ?: notice
        if (amountTarget != null) {
            quantityNodes.filter { it.pack == DomainSemanticPackId.FINANCE }.forEach { amount ->
                relations += relation(amountTarget, amount, DomainSemanticRelationType.HAS_AMOUNT)
            }
        }
        if (notice != null && deadline != null && notice.id != deadline.id) {
            relations += relation(notice, deadline, DomainSemanticRelationType.HAS_DEADLINE)
        }
        if (deadline != null && temporalNodes.isNotEmpty()) {
            temporalNodes.forEach { temporal ->
                if (deadline.id != temporal.id) {
                    relations += relation(deadline, temporal, DomainSemanticRelationType.RELATES_TO)
                }
            }
        }

        actionGraph.nodes.forEach { action ->
            when (action.frame.predicate) {
                PredicateConcept.OWE -> {
                    val debtor = action.frame.roles[SemanticRole.DEBTOR]?.let {
                        addNode(DomainSemanticPackId.DEBT, "debt.debtor", it.normalized, it.confidence)
                    }
                    val creditor = action.frame.roles[SemanticRole.CREDITOR]?.let {
                        addNode(DomainSemanticPackId.DEBT, "debt.creditor", it.normalized, it.confidence)
                    }
                    if (debtor != null && creditor != null) {
                        relations += relation(debtor, creditor, DomainSemanticRelationType.OWES_TO)
                    }
                    val amount = quantityNodes.firstOrNull()
                    if (debtor != null && amount != null) {
                        relations += relation(debtor, amount, DomainSemanticRelationType.HAS_AMOUNT)
                    }
                }
                PredicateConcept.PAY -> {
                    val amount = quantityNodes.firstOrNull()
                    val recipient = action.frame.roles[SemanticRole.RECIPIENT]?.let {
                        addNode(DomainSemanticPackId.FINANCE, "finance.recipient", it.normalized, it.confidence)
                    }
                    if (amount != null && recipient != null) {
                        relations += relation(amount, recipient, DomainSemanticRelationType.PAYS_TO)
                    }
                }
                PredicateConcept.COMMUNICATE -> {
                    val recipient = action.frame.roles[SemanticRole.RECIPIENT]?.let {
                        addNode(
                            DomainSemanticPackId.COMMUNICATION,
                            "communication.recipient",
                            it.normalized,
                            it.confidence,
                        )
                    }
                    val objectNode = action.frame.roles[SemanticRole.OBJECT]?.let {
                        addNode(
                            DomainSemanticPackId.COMMUNICATION,
                            "communication.object",
                            it.normalized,
                            it.confidence,
                        )
                    }
                    if (objectNode != null && recipient != null) {
                        relations += relation(
                            objectNode,
                            recipient,
                            DomainSemanticRelationType.HAS_RECIPIENT,
                        )
                    }
                }
                else -> Unit
            }
        }

        val canonicalNodes = nodes
            .distinctBy { it.id }
            .sortedBy { it.id.value }
        val canonicalRelations = relations
            .distinctBy { Triple(it.from, it.to, it.type) }
            .sortedWith(
                compareBy<DomainSemanticRelation> { it.from.value }
                    .thenBy { it.to.value }
                    .thenBy { it.type.name }
            )
        val packs = canonicalNodes.mapTo(linkedSetOf()) { it.pack }

        val fingerprint = StableCognitiveIds.fingerprint(
            "domain-semantic-graph/v1",
            utterance.language.name,
            *buildList {
                canonicalNodes.forEach {
                    add(
                        "n:" + it.id.value + ":" + it.pack.name + ":" + it.type + ":" +
                            it.value + ":" + java.lang.Double.toHexString(it.confidence)
                    )
                }
                canonicalRelations.forEach {
                    add(
                        "r:" + it.from.value + ":" + it.to.value + ":" + it.type.name + ":" +
                            java.lang.Double.toHexString(it.confidence)
                    )
                }
            }.toTypedArray(),
        )
        return DomainSemanticGraph(packs, canonicalNodes, canonicalRelations, fingerprint)
    }

    private fun relation(
        from: DomainSemanticNode,
        to: DomainSemanticNode,
        type: DomainSemanticRelationType,
    ): DomainSemanticRelation = DomainSemanticRelation(
        from = from.id,
        to = to.id,
        type = type,
        confidence = minOf(from.confidence, to.confidence),
    )

    private fun packFor(type: SemanticEntityTypeId): DomainSemanticPackId = when {
        type.value.startsWith("authority.") -> DomainSemanticPackId.AUTHORITY
        type.value.startsWith("debt.") -> DomainSemanticPackId.DEBT
        type.value.startsWith("finance.") || type.value.startsWith("quantity.") -> DomainSemanticPackId.FINANCE
        type.value.startsWith("appointment.") || type.value.startsWith("temporal.") -> DomainSemanticPackId.APPOINTMENT
        type.value.startsWith("communication.") -> DomainSemanticPackId.COMMUNICATION
        type.value.startsWith("contract.") -> DomainSemanticPackId.CONTRACT
        type.value.startsWith("health.") -> DomainSemanticPackId.HEALTH
        else -> DomainSemanticPackId.DOCUMENTS
    }
}
