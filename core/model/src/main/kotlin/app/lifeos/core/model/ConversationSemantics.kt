package app.lifeos.core.model

enum class SemanticAct { STATEMENT, QUESTION, TASK, CORRECTION, CONFIRMATION, NEGATION, CONTINUATION, UNKNOWN }
enum class KnowledgeState { KNOWN, UNKNOWN, CONFLICTED, NOT_APPLICABLE, UNOBSERVED, UNAVAILABLE }

data class UncertaintyVector(
    val semantic: Long,
    val temporal: Long,
    val identity: Long,
    val causal: Long,
    val retrieval: Long,
    val evidence: Long,
) {
    init { listOf(semantic, temporal, identity, causal, retrieval, evidence).forEach { require(it in 0..1_000_000L) } }
    val maximum: Long get() = maxOf(semantic, temporal, identity, causal, retrieval, evidence)
}

data class SemanticTurn(
    val textFingerprint: String,
    val act: SemanticAct,
    val entityKeys: Set<String>,
    val referenceKeys: Set<String>,
    val temporalKeys: Set<String>,
    val constraintKeys: Set<String>,
    val uncertainty: UncertaintyVector,
) {
    init {
        require(textFingerprint.isNotBlank())
        require((entityKeys + referenceKeys + temporalKeys + constraintKeys).none { it.isBlank() })
    }
}

data class ConversationWorkingContext(
    val currentTurnFingerprint: String,
    val recentTurnFingerprints: List<String>,
    val activeEntityKeys: Set<String>,
    val activeGoalId: String?,
    val relevantMemoryKeys: Set<String>,
    val threadId: String,
) {
    init {
        require(currentTurnFingerprint.isNotBlank())
        require(recentTurnFingerprints.none { it.isBlank() })
        require(activeEntityKeys.none { it.isBlank() })
        require(relevantMemoryKeys.none { it.isBlank() })
        require(threadId.isNotBlank())
    }
}

data class ResolvedReference(
    val surfaceKey: String,
    val targetKey: String?,
    val knowledgeState: KnowledgeState,
    val uncertaintyMicros: Long,
) {
    init {
        require(surfaceKey.isNotBlank())
        require(targetKey?.isNotBlank() != false)
        require(uncertaintyMicros in 0..1_000_000L)
        if (knowledgeState == KnowledgeState.KNOWN) require(targetKey != null)
    }
}

data class CognitiveTimeEnvelope(
    val systemRevision: Long,
    val domainTimeMillis: Long?,
    val observedTimeMillis: Long,
    val validFromMillis: Long?,
    val validUntilMillis: Long?,
    val supersededTimeMillis: Long?,
) {
    init {
        require(systemRevision > 0)
        require(observedTimeMillis >= 0)
        if (validFromMillis != null && validUntilMillis != null) require(validUntilMillis >= validFromMillis)
        if (supersededTimeMillis != null) require(supersededTimeMillis >= observedTimeMillis)
    }
}

data class ConflictActivation(
    val conflictId: String,
    val triggerKeys: Set<String>,
    val conflictEnergyMicros: Long,
    val knowledgeState: KnowledgeState = KnowledgeState.CONFLICTED,
) {
    init {
        require(conflictId.isNotBlank())
        require(triggerKeys.isNotEmpty() && triggerKeys.none { it.isBlank() })
        require(conflictEnergyMicros in 0..1_000_000L)
        require(knowledgeState == KnowledgeState.CONFLICTED)
    }
}
