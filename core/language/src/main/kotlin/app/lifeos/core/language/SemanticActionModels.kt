package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

data class TextSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive > start)
    }

    fun contains(other: TextSpan): Boolean =
        other.start >= start && other.endExclusive <= endExclusive

    fun overlaps(other: TextSpan): Boolean =
        start < other.endExclusive && other.start < endExclusive
}

data class SemanticEvidence(
    val source: String,
    val detail: String,
    val strength: Double,
    val span: TextSpan? = null,
) {
    init {
        require(source.isNotBlank())
        require(detail.isNotBlank())
        require(strength.isFinite() && strength in 0.0..1.0)
    }
}

enum class SpeechActType {
    QUESTION,
    COMMAND,
    REQUEST,
    ASSERTION,
    CONFIRMATION,
    CORRECTION,
    HYPOTHETICAL,
    QUOTATION,
    GREETING,
    ACKNOWLEDGEMENT,
    UNKNOWN,
}

data class SpeechAct(
    val type: SpeechActType,
    val confidence: Double,
    val evidence: List<SemanticEvidence>,
    val span: TextSpan,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidence.isNotEmpty())
    }
}

enum class ScopeType {
    NEGATION,
    MODALITY,
    CONDITION,
    QUOTATION,
    HYPOTHETICAL,
    CONTRAST,
    EXCLUSION,
}

@JvmInline
value class SemanticNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "semantic-node:"
        fun create(vararg parts: String): SemanticNodeId =
            SemanticNodeId(PREFIX + StableCognitiveIds.fingerprint("semantic-node/v1", *parts))
    }
}

data class SemanticScope(
    val type: ScopeType,
    val targetNodeIds: Set<SemanticNodeId>,
    val span: TextSpan,
    val cue: String,
    val confidence: Double,
) {
    init {
        require(targetNodeIds.isNotEmpty())
        require(cue.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

enum class PredicateConcept {
    CREATE_IMAGE,
    TRANSFORM_IMAGE,
    SEARCH,
    CONTINUE,
    BUILD,
    QUERY,
    SCHEDULE,
    COMMUNICATE,
    STORE_MEMORY,
    OWE,
    PAY,
    DELETE,
    UPLOAD,
    SELECT,
    CONDITION_CHECK,
    UNKNOWN,
}

enum class SemanticRole {
    AGENT,
    ACTION,
    PATIENT,
    OBJECT,
    RECIPIENT,
    SOURCE,
    DESTINATION,
    OWNER,
    BENEFICIARY,
    INSTRUMENT,
    LOCATION,
    TIME,
    DATE,
    DURATION,
    AMOUNT,
    UNIT,
    CURRENCY,
    CAUSE,
    PURPOSE,
    CONDITION,
    ATTRIBUTE,
    DEBTOR,
    CREDITOR,
}

data class SemanticValue(
    val rawText: String,
    val normalized: String,
    val entityType: EntityType? = null,
    val quantity: SemanticQuantity? = null,
    val referencePhoton: PhotonRevisionRef? = null,
    val resolved: Boolean = true,
    val confidence: Double = 1.0,
) {
    init {
        require(rawText.isNotBlank())
        require(normalized.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(referencePhoton == null || resolved)
    }
}

data class PredicateFrame(
    val nodeId: SemanticNodeId,
    val clauseId: Int,
    val predicate: PredicateConcept,
    val roles: Map<SemanticRole, SemanticValue>,
    val scopeTypes: Set<ScopeType>,
    val speechAct: SpeechAct,
    val confidence: Double,
    val evidence: List<SemanticEvidence>,
) {
    init {
        require(clauseId >= 0)
        require(predicate != PredicateConcept.UNKNOWN)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidence.isNotEmpty())
    }

    val negated: Boolean get() = ScopeType.NEGATION in scopeTypes
    val quoted: Boolean get() = ScopeType.QUOTATION in scopeTypes
    val hypothetical: Boolean get() = ScopeType.HYPOTHETICAL in scopeTypes
    val conditional: Boolean get() = ScopeType.CONDITION in scopeTypes
}

enum class SemanticActionNodeType {
    ACTION,
    QUERY,
    ASSERTION,
    CONDITION,
    REFERENCE,
}

enum class SemanticActionEdgeType {
    AND,
    OR,
    THEN,
    BEFORE,
    AFTER,
    IF,
    ELSE,
    DEPENDS_ON,
    USES_RESULT_OF,
    CAUSES,
    PURPOSE_OF,
}

data class SemanticActionNode(
    val id: SemanticNodeId,
    val type: SemanticActionNodeType,
    val frame: PredicateFrame,
    val requiredRoles: Set<SemanticRole>,
    val unresolvedRoles: Set<SemanticRole>,
    val unresolvedReference: Boolean,
    val unresolvedCondition: Boolean,
    val externalSideEffect: Boolean,
    val executionReadiness: Double,
) {
    init {
        require(unresolvedRoles.all { it in requiredRoles })
        require(executionReadiness.isFinite() && executionReadiness in 0.0..1.0)
    }

    val executable: Boolean
        get() = type == SemanticActionNodeType.ACTION &&
            frame.speechAct.type in setOf(SpeechActType.COMMAND, SpeechActType.REQUEST) &&
            !frame.negated &&
            !frame.quoted &&
            !frame.hypothetical &&
            !unresolvedCondition &&
            unresolvedRoles.isEmpty() &&
            !unresolvedReference &&
            executionReadiness >= MIN_EXECUTION_READINESS

    companion object {
        const val MIN_EXECUTION_READINESS = 0.75
    }
}

data class SemanticActionEdge(
    val from: SemanticNodeId,
    val to: SemanticNodeId,
    val type: SemanticActionEdgeType,
    val confidence: Double,
) {
    init {
        require(from != to)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class SemanticActionGraph(
    val nodes: List<SemanticActionNode>,
    val edges: List<SemanticActionEdge>,
    val scopes: List<SemanticScope>,
    val fingerprint: String,
) {
    init {
        require(nodes.map { it.id }.distinct().size == nodes.size)
        val ids = nodes.mapTo(linkedSetOf()) { it.id }
        require(edges.all { it.from in ids && it.to in ids })
        require(scopes.all { scope -> scope.targetNodeIds.isNotEmpty() && scope.targetNodeIds.all { it in ids } })
        require(fingerprint.isNotBlank())
    }

    val executableNodes: List<SemanticActionNode>
        get() = nodes.filter { it.executable }

    fun executableNodeFor(intent: IntentType): SemanticActionNode? {
        val predicate = intent.toPredicateConcept()
        return executableNodes.singleOrNull { it.frame.predicate == predicate }
    }

    fun hasExecutableExternalSideEffect(): Boolean =
        executableNodes.any { it.externalSideEffect }

    companion object {
        fun empty(): SemanticActionGraph = SemanticActionGraph(
            nodes = emptyList(),
            edges = emptyList(),
            scopes = emptyList(),
            fingerprint = StableCognitiveIds.fingerprint("semantic-action-graph/v1", "empty"),
        )
    }
}

fun PredicateConcept.toIntentTypeOrNull(): IntentType? = when (this) {
    PredicateConcept.CREATE_IMAGE -> IntentType.CREATE_IMAGE
    PredicateConcept.TRANSFORM_IMAGE -> IntentType.TRANSFORM_IMAGE
    PredicateConcept.SEARCH -> IntentType.SEARCH
    PredicateConcept.CONTINUE -> IntentType.CONTINUE
    PredicateConcept.BUILD -> IntentType.BUILD_OR_IMPLEMENT
    PredicateConcept.QUERY -> IntentType.QUERY
    PredicateConcept.SCHEDULE -> IntentType.SCHEDULE
    PredicateConcept.COMMUNICATE -> IntentType.COMMUNICATE
    PredicateConcept.STORE_MEMORY -> IntentType.STORE_OR_REMEMBER
    PredicateConcept.OWE,
    PredicateConcept.PAY,
    PredicateConcept.DELETE,
    PredicateConcept.UPLOAD,
    PredicateConcept.SELECT,
    PredicateConcept.CONDITION_CHECK,
    PredicateConcept.UNKNOWN -> null
}

fun IntentType.toPredicateConcept(): PredicateConcept = when (this) {
    IntentType.CREATE_IMAGE -> PredicateConcept.CREATE_IMAGE
    IntentType.TRANSFORM_IMAGE -> PredicateConcept.TRANSFORM_IMAGE
    IntentType.SEARCH -> PredicateConcept.SEARCH
    IntentType.CONTINUE -> PredicateConcept.CONTINUE
    IntentType.BUILD_OR_IMPLEMENT -> PredicateConcept.BUILD
    IntentType.QUERY -> PredicateConcept.QUERY
    IntentType.SCHEDULE -> PredicateConcept.SCHEDULE
    IntentType.COMMUNICATE -> PredicateConcept.COMMUNICATE
    IntentType.STORE_OR_REMEMBER -> PredicateConcept.STORE_MEMORY
    IntentType.CONVERSATION,
    IntentType.UNKNOWN -> PredicateConcept.UNKNOWN
}
