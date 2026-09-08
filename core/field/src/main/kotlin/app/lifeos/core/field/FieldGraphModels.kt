package app.lifeos.core.field

enum class FieldNodeKind {
    SOURCE,
    EVIDENCE,
    CONCEPT,
    ENTITY,
    STATE,
    CLAIM,
    HYPOTHESIS,
    CONSTRAINT,
    OUTCOME,
    CONFLICT,
}

data class FieldNode(
    val id: FieldNodeId,
    val domainId: FieldDomainId,
    val kind: FieldNodeKind,
    val semanticKey: String,
    val semanticMass: Double = 1.0,
    val baseEnergy: Double = 0.0,
    val evidenceIds: Set<EvidenceId> = emptySet(),
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(semanticKey.isNotBlank()) { "Field node semantic key must not be blank" }
        require(semanticMass.isFinite() && semanticMass >= 0.0) {
            "Semantic mass must be finite and non-negative"
        }
        require(baseEnergy.isFinite() && baseEnergy >= 0.0) {
            "Base energy must be finite and non-negative"
        }
        require(attributes.keys.none { it.isBlank() }) { "Field node attribute keys must not be blank" }
    }

    companion object {
        fun create(
            domainId: FieldDomainId,
            kind: FieldNodeKind,
            semanticKey: String,
            semanticMass: Double = 1.0,
            baseEnergy: Double = 0.0,
            evidenceIds: Set<EvidenceId> = emptySet(),
            attributes: Map<String, String> = emptyMap(),
        ): FieldNode = FieldNode(
            id = StableFieldIds.node(domainId, kind.name, semanticKey),
            domainId = domainId,
            kind = kind,
            semanticKey = semanticKey,
            semanticMass = semanticMass,
            baseEnergy = baseEnergy,
            evidenceIds = evidenceIds,
            attributes = attributes,
        )
    }
}

enum class FieldRelationType {
    SUPPORTS,
    CONTRADICTS,
    ATTRACTS,
    REPELS,
    CONSTRAINS,
    DEPENDS_ON,
    DERIVED_FROM,
    TEMPORALLY_PRECEDES,
    TEMPORALLY_FOLLOWS,
    REFERS_TO,
}

data class FieldRelation(
    val id: FieldRelationId,
    val domainId: FieldDomainId,
    val source: FieldNodeId,
    val target: FieldNodeId,
    val type: FieldRelationType,
    val weight: Double,
    val explanation: String,
) {
    init {
        require(source != target) { "Field relation cannot point to itself" }
        require(weight in 0.0..1.0) { "Field relation weight must be in 0..1" }
        require(explanation.isNotBlank()) { "Field relation explanation must not be blank" }
    }

    companion object {
        fun create(
            domainId: FieldDomainId,
            source: FieldNodeId,
            target: FieldNodeId,
            type: FieldRelationType,
            weight: Double,
            explanation: String,
        ): FieldRelation = FieldRelation(
            id = StableFieldIds.relation(domainId, source, target, type.name),
            domainId = domainId,
            source = source,
            target = target,
            type = type,
            weight = weight,
            explanation = explanation,
        )
    }
}

enum class ForcePolarity { ATTRACTION, REPULSION, CONSTRAINT }

data class FieldForce(
    val sourceNodeId: FieldNodeId,
    val targetNodeId: FieldNodeId,
    val polarity: ForcePolarity,
    val magnitude: Double,
    val reason: String,
    val evidenceIds: Set<EvidenceId> = emptySet(),
) {
    init {
        require(sourceNodeId != targetNodeId) { "Field force cannot target its source" }
        require(magnitude.isFinite() && magnitude >= 0.0) { "Force magnitude must be finite and non-negative" }
        require(reason.isNotBlank()) { "Field force reason must not be blank" }
    }

    val signedMagnitude: Double
        get() = when (polarity) {
            ForcePolarity.ATTRACTION -> magnitude
            ForcePolarity.REPULSION -> -magnitude
            ForcePolarity.CONSTRAINT -> 0.0
        }
}

data class CompetitionGroup(
    val key: String,
    val nodeIds: Set<FieldNodeId>,
    val allowUnresolved: Boolean = true,
) {
    init {
        require(key.isNotBlank()) { "Competition group key must not be blank" }
        require(nodeIds.size >= 2) { "Competition group requires at least two nodes" }
    }
}

data class FieldConflict(
    val key: String,
    val nodeIds: Set<FieldNodeId>,
    val severity: Double,
    val evidenceIds: Set<EvidenceId>,
    val explanation: String,
) {
    init {
        require(key.isNotBlank()) { "Conflict key must not be blank" }
        require(nodeIds.size >= 2) { "Conflict requires at least two nodes" }
        require(severity in 0.0..1.0) { "Conflict severity must be in 0..1" }
        require(explanation.isNotBlank()) { "Conflict explanation must not be blank" }
    }
}

data class FieldGraph(
    val domainId: FieldDomainId,
    val nodes: List<FieldNode>,
    val relations: List<FieldRelation> = emptyList(),
    val competitionGroups: List<CompetitionGroup> = emptyList(),
    val conflicts: List<FieldConflict> = emptyList(),
) {
    init {
        require(nodes.map { it.id }.distinct().size == nodes.size) { "Field graph node ids must be unique" }
        require(relations.map { it.id }.distinct().size == relations.size) { "Field relation ids must be unique" }
        require(nodes.all { it.domainId == domainId }) { "All nodes must belong to graph domain" }
        require(relations.all { it.domainId == domainId }) { "All relations must belong to graph domain" }

        val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
        require(relations.all { it.source in nodeIds && it.target in nodeIds }) {
            "Relations must reference nodes in the graph"
        }
        require(competitionGroups.all { group -> group.nodeIds.all(nodeIds::contains) }) {
            "Competition groups must reference nodes in the graph"
        }
        require(conflicts.all { conflict -> conflict.nodeIds.all(nodeIds::contains) }) {
            "Conflicts must reference nodes in the graph"
        }
    }

    private val nodeById: Map<FieldNodeId, FieldNode> = nodes.associateBy { it.id }

    fun node(id: FieldNodeId): FieldNode? = nodeById[id]

    fun outgoing(id: FieldNodeId): List<FieldRelation> = relations
        .asSequence()
        .filter { it.source == id }
        .sortedBy { it.id.value }
        .toList()

    fun incoming(id: FieldNodeId): List<FieldRelation> = relations
        .asSequence()
        .filter { it.target == id }
        .sortedBy { it.id.value }
        .toList()

    fun stableNodes(): List<FieldNode> = nodes.sortedBy { it.id.value }
    fun stableRelations(): List<FieldRelation> = relations.sortedBy { it.id.value }
}
