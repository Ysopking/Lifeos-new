package app.lifeos.next.ui.decision

import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLinkType
import app.lifeos.core.runtime.trace.DecisionTraceNodeId
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import java.time.Instant

enum class DecisionTraceKind {
    GOAL,
    SELF_HEALING,
    EVOLUTION,
    ARTIFACT,
    SYSTEM,
}

enum class DecisionTraceSection {
    FACT,
    CONSTRAINT,
    ALTERNATIVE,
    OUTCOME,
    UNCERTAINTY,
}

enum class DecisionTraceTone {
    NEUTRAL,
    POSITIVE,
    WARNING,
    NEGATIVE,
}

data class DecisionTraceReasonUiModel(
    val raw: String,
    val summary: String,
)

data class DecisionTraceNodeUiModel(
    val id: DecisionTraceNodeId,
    val type: DecisionTraceNodeType,
    val sourceType: String,
    val sourceId: String,
    val sourceRevision: Long,
    val label: String,
    val reasons: List<DecisionTraceReasonUiModel>,
    val recordedAt: Instant,
    val section: DecisionTraceSection,
    val tone: DecisionTraceTone,
)

data class DecisionTraceLinkUiModel(
    val from: DecisionTraceNodeId,
    val to: DecisionTraceNodeId,
    val type: DecisionTraceLinkType,
)

data class DecisionTraceUiModel(
    val traceId: DecisionTraceId,
    val revision: Long,
    val kind: DecisionTraceKind,
    val title: String,
    val summary: String,
    val firstRecordedAt: Instant?,
    val lastRecordedAt: Instant?,
    val unresolved: Boolean,
    val facts: List<DecisionTraceNodeUiModel>,
    val constraints: List<DecisionTraceNodeUiModel>,
    val alternatives: List<DecisionTraceNodeUiModel>,
    val outcomes: List<DecisionTraceNodeUiModel>,
    val uncertainties: List<DecisionTraceNodeUiModel>,
    val links: List<DecisionTraceLinkUiModel>,
) {
    val nodeCount: Int
        get() = facts.size + constraints.size + alternatives.size + outcomes.size + uncertainties.size
}

data class DecisionTraceWorkspaceUiModel(
    val traces: List<DecisionTraceUiModel>,
) {
    val unresolvedCount: Int
        get() = traces.count { it.unresolved }

    companion object {
        fun empty(): DecisionTraceWorkspaceUiModel = DecisionTraceWorkspaceUiModel(emptyList())
    }
}
