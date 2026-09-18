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

class DomainSemanticInterpreter(
    private val contextBuilder: DomainClauseContextBuilder = DomainClauseContextBuilder(),
) {
    fun interpret(
        utterance: NormalizedUtterance,
        semanticGraph: LanguageSemanticGraph = LanguageSemanticGraph.empty(utterance.language),
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
            sourceAnchor: String = "",
        ): DomainSemanticNode {
            val node = DomainSemanticNode(
                id = DomainSemanticNodeId.create(
                    pack.name,
                    type,
                    value,
                    sourceType?.value.orEmpty(),
                    sourceAnchor,
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
                sourceAnchor = "entity:" + entity.tokenStart + ":" + entity.tokenEndExclusive,
            )
        }

        val quantityNodes = quantityTemporal.quantities.associateWith { quantity ->
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
                sourceAnchor = "quantity:" + quantity.span.start + ":" + quantity.span.endExclusive,
            )
        }

        val temporalNodes = quantityTemporal.temporals.associateWith { temporal ->
            addNode(
                pack = DomainSemanticPackId.APPOINTMENT,
                type = "temporal." + temporal.relation.name.lowercase(),
                value = listOf(
                    temporal.startInclusive?.toString().orEmpty(),
                    temporal.endInclusive?.toString().orEmpty(),
                ).joinToString(":"),
                confidence = temporal.confidence,
                sourceAnchor = "temporal:" + temporal.span.start + ":" + temporal.span.endExclusive,
            )
        }

        val contexts = contextBuilder.build(
            utterance = utterance,
            semanticGraph = semanticGraph,
            entities = entities,
            quantityTemporal = quantityTemporal,
            actionGraph = actionGraph,
        )

        fun tokenCharStart(entity: SemanticEntityV2): Int =
            utterance.tokens.getOrNull(entity.tokenStart)?.start ?: Int.MAX_VALUE

        contexts.forEach { context ->
            val localEntities = context.entities.associateWith { entityNodes.getValue(it) }
            val localQuantities = context.quantities.mapNotNull(quantityNodes::get)
            val localTemporals = context.temporals.mapNotNull(temporalNodes::get)

            fun entitiesOf(types: Set<SemanticEntityTypeId>): List<Pair<SemanticEntityV2, DomainSemanticNode>> =
                localEntities.entries
                    .filter { it.key.typeId in types }
                    .sortedBy { it.key.tokenStart }
                    .map { it.key to it.value }

            fun nearestBefore(
                target: SemanticEntityV2,
                candidates: List<Pair<SemanticEntityV2, DomainSemanticNode>>,
            ): DomainSemanticNode? =
                candidates
                    .filter { it.first.tokenStart <= target.tokenStart }
                    .maxByOrNull { it.first.tokenStart }
                    ?.second
                    ?: candidates.minByOrNull { kotlin.math.abs(it.first.tokenStart - target.tokenStart) }?.second

            val authorities = entitiesOf(setOf(EntityTypeRegistry.AUTHORITY.id))
            val notices = entitiesOf(
                setOf(
                    EntityTypeRegistry.NOTICE.id,
                    EntityTypeRegistry.INVOICE.id,
                    EntityTypeRegistry.APPLICATION.id,
                    EntityTypeRegistry.CONTRACT.id,
                    EntityTypeRegistry.DOCUMENT.id,
                )
            )
            val claims = entitiesOf(setOf(EntityTypeRegistry.CLAIM.id, EntityTypeRegistry.DEBT.id))
            val deadlines = entitiesOf(setOf(EntityTypeRegistry.DEADLINE.id))

            notices.forEach { (noticeEntity, noticeNode) ->
                nearestBefore(noticeEntity, authorities)?.let { authority ->
                    relations += relation(authority, noticeNode, DomainSemanticRelationType.ISSUED)
                }
            }

            claims.forEach { (claimEntity, claimNode) ->
                nearestBefore(claimEntity, notices)?.let { document ->
                    relations += relation(document, claimNode, DomainSemanticRelationType.CONTAINS)
                }

                val claimCharStart = tokenCharStart(claimEntity)
                localQuantities
                    .filter { it.pack == DomainSemanticPackId.FINANCE }
                    .minByOrNull { amount ->
                        val source = context.quantities.firstOrNull { quantityNodes[it]?.id == amount.id }
                        kotlin.math.abs((source?.span?.start ?: claimCharStart) - claimCharStart)
                    }
                    ?.let { amount ->
                        relations += relation(claimNode, amount, DomainSemanticRelationType.HAS_AMOUNT)
                    }
            }

            if (claims.isEmpty() && notices.isNotEmpty()) {
                localQuantities
                    .filter { it.pack == DomainSemanticPackId.FINANCE }
                    .forEach { amount ->
                        val source = context.quantities.firstOrNull { quantityNodes[it]?.id == amount.id }
                        val amountStart = source?.span?.start ?: Int.MAX_VALUE
                        notices
                            .minByOrNull { (documentEntity, _) ->
                                kotlin.math.abs(tokenCharStart(documentEntity) - amountStart)
                            }
                            ?.second
                            ?.let { document ->
                                relations += relation(
                                    document,
                                    amount,
                                    DomainSemanticRelationType.HAS_AMOUNT,
                                )
                            }
                    }
            }

            deadlines.forEach { (deadlineEntity, deadlineNode) ->
                nearestBefore(deadlineEntity, notices)?.let { document ->
                    relations += relation(document, deadlineNode, DomainSemanticRelationType.HAS_DEADLINE)
                }
                localTemporals.forEach { temporal ->
                    if (deadlineNode.id != temporal.id) {
                        relations += relation(deadlineNode, temporal, DomainSemanticRelationType.RELATES_TO)
                    }
                }
            }

            entitiesOf(setOf(EntityTypeRegistry.APPOINTMENT.id)).forEach { (_, appointment) ->
                localTemporals.forEach { temporal ->
                    if (appointment.id != temporal.id) {
                        relations += relation(appointment, temporal, DomainSemanticRelationType.RELATES_TO)
                    }
                }
            }

            entitiesOf(setOf(EntityTypeRegistry.CONTRACT.id)).forEach { (_, contractNode) ->
                entitiesOf(setOf(EntityTypeRegistry.DURATION.id)).forEach { (_, duration) ->
                    relations += relation(contractNode, duration, DomainSemanticRelationType.HAS_DURATION)
                }
                deadlines.firstOrNull()?.second?.let { deadline ->
                    if (contractNode.id != deadline.id) {
                        relations += relation(contractNode, deadline, DomainSemanticRelationType.HAS_DEADLINE)
                    }
                }
            }

            entitiesOf(setOf(EntityTypeRegistry.MEDICATION.id)).forEach { (_, medication) ->
                val dosageNodes = entitiesOf(setOf(EntityTypeRegistry.DOSAGE.id)).map { it.second }
                    .ifEmpty { localQuantities.filter { it.type == "quantity.value" } }
                dosageNodes.forEach { dosage ->
                    if (dosage.id != medication.id) {
                        relations += relation(medication, dosage, DomainSemanticRelationType.HAS_DOSAGE)
                    }
                }
            }

            context.frames.forEach { frame ->
                when (frame.predicate) {
                    PredicateConcept.OWE -> {
                        val debtor = frame.roles[SemanticRole.DEBTOR]?.let {
                            addNode(
                                DomainSemanticPackId.DEBT,
                                "debt.debtor",
                                it.normalized,
                                it.confidence,
                                sourceAnchor = "frame:" + frame.nodeId.value + ":debtor",
                            )
                        }
                        val creditor = frame.roles[SemanticRole.CREDITOR]?.let {
                            addNode(
                                DomainSemanticPackId.DEBT,
                                "debt.creditor",
                                it.normalized,
                                it.confidence,
                                sourceAnchor = "frame:" + frame.nodeId.value + ":creditor",
                            )
                        }
                        if (debtor != null && creditor != null) {
                            relations += relation(debtor, creditor, DomainSemanticRelationType.OWES_TO)
                        }
                        val amount = localQuantities.firstOrNull()
                        if (debtor != null && amount != null) {
                            relations += relation(debtor, amount, DomainSemanticRelationType.HAS_AMOUNT)
                        }
                    }
                    PredicateConcept.PAY -> {
                        val amount = localQuantities.firstOrNull()
                        val recipient = frame.roles[SemanticRole.RECIPIENT]?.let {
                            addNode(
                                DomainSemanticPackId.FINANCE,
                                "finance.recipient",
                                it.normalized,
                                it.confidence,
                                sourceAnchor = "frame:" + frame.nodeId.value + ":recipient",
                            )
                        }
                        if (amount != null && recipient != null) {
                            relations += relation(amount, recipient, DomainSemanticRelationType.PAYS_TO)
                        }
                    }
                    PredicateConcept.COMMUNICATE -> {
                        val recipient = frame.roles[SemanticRole.RECIPIENT]?.let {
                            addNode(
                                DomainSemanticPackId.COMMUNICATION,
                                "communication.recipient",
                                it.normalized,
                                it.confidence,
                                sourceAnchor = "frame:" + frame.nodeId.value + ":recipient",
                            )
                        }
                        val objectNode = frame.roles[SemanticRole.OBJECT]?.let {
                            addNode(
                                DomainSemanticPackId.COMMUNICATION,
                                "communication.object",
                                it.normalized,
                                it.confidence,
                                sourceAnchor = "frame:" + frame.nodeId.value + ":object",
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
        }

        val contextsByClause = contexts.associateBy { it.clauseId }
        semanticGraph.links
            .asSequence()
            .filter { it.type in DOMAIN_CARRY_LINKS }
            .sortedWith(
                compareBy<SemanticLink> { it.fromClauseId }
                    .thenBy { it.toClauseId }
                    .thenBy { it.type.name }
            )
            .forEach { link ->
                val source = contextsByClause[link.fromClauseId] ?: return@forEach
                val target = contextsByClause[link.toClauseId] ?: return@forEach
                val sourceDocument = source.entities
                    .filter { it.typeId in DOCUMENT_ENTITY_TYPES }
                    .maxByOrNull { it.tokenStart }
                    ?.let(entityNodes::get)
                    ?: return@forEach
                target.entities
                    .filter { it.typeId == EntityTypeRegistry.DEADLINE.id }
                    .sortedBy { it.tokenStart }
                    .mapNotNull(entityNodes::get)
                    .forEach { deadline ->
                        if (sourceDocument.id != deadline.id) {
                            relations += relation(
                                sourceDocument,
                                deadline,
                                DomainSemanticRelationType.HAS_DEADLINE,
                            )
                        }
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

    private companion object {
        val DOMAIN_CARRY_LINKS = setOf(
            SemanticLinkType.CONJUNCTION,
            SemanticLinkType.SEQUENCE,
        )
        val DOCUMENT_ENTITY_TYPES = setOf(
            EntityTypeRegistry.NOTICE.id,
            EntityTypeRegistry.INVOICE.id,
            EntityTypeRegistry.APPLICATION.id,
            EntityTypeRegistry.CONTRACT.id,
            EntityTypeRegistry.DOCUMENT.id,
        )
    }

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
