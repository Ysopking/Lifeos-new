package app.lifeos.core.runtime

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceNodeType

/**
 * Read-only cognitive view over the canonical durable DecisionTrace.
 *
 * This type owns no persistence and no independent trace identity. All fields are derived from the
 * supplied DecisionTrace revision so subsystem-specific views cannot drift into a second truth.
 */
data class CognitiveDecisionTrace(
    val canonical: DecisionTrace,
    val inputs: Set<PhotonRevisionRef>,
    val interpretationIds: Set<String>,
    val dependencyFingerprints: Set<String>,
    val fieldIds: Set<String>,
    val matterIds: Set<String>,
    val goalIds: Set<String>,
    val decisionId: String?,
    val artifactIds: Set<String>,
    val actionIds: Set<String>,
    val outcomeIds: Set<String>,
) {
    val traceId: String get() = canonical.id.value
    val revision: Long get() = canonical.revision

    companion object {
        fun from(trace: DecisionTrace): CognitiveDecisionTrace {
            val nodes = trace.nodes
            val exactInputs = nodes.asSequence()
                .filter { it.sourceType == "photon-revision" && it.sourceRevision > 0L }
                .map { PhotonRevisionRef(PhotonId(it.sourceId), it.sourceRevision) }
                .toCollection(linkedSetOf())

            fun idsMatching(predicate: (String) -> Boolean): Set<String> =
                nodes.asSequence()
                    .filter { predicate(it.sourceType.lowercase()) }
                    .mapTo(linkedSetOf()) { it.sourceId }

            val decisions = nodes.asSequence()
                .filter {
                    it.type == DecisionTraceNodeType.SELECTION ||
                        it.type == DecisionTraceNodeType.REJECTION
                }
                .map { it.sourceId }
                .sorted()
                .toList()

            val outcomes = nodes.asSequence()
                .filter {
                    it.type == DecisionTraceNodeType.EXECUTION_OUTCOME ||
                        it.type == DecisionTraceNodeType.RECOVERY_OUTCOME
                }
                .mapTo(linkedSetOf()) { it.sourceId }

            return CognitiveDecisionTrace(
                canonical = trace,
                inputs = exactInputs,
                interpretationIds = idsMatching { "interpretation" in it },
                dependencyFingerprints = idsMatching { "dependency" in it },
                fieldIds = idsMatching { it == "field" || it.endsWith("-field") || "field-" in it },
                matterIds = idsMatching { "matter" in it },
                goalIds = idsMatching { "goal" in it },
                decisionId = decisions.firstOrNull(),
                artifactIds = idsMatching { "artifact" in it },
                actionIds = idsMatching { "action" in it || "effect" in it },
                outcomeIds = outcomes,
            )
        }
    }
}
