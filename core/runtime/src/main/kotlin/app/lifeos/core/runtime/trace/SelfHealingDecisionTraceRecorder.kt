package app.lifeos.core.runtime.trace

import app.lifeos.core.runtime.health.RecoveryPlan
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentState
import kotlinx.coroutines.CancellationException

/**
 * Non-authoritative V15 projection over the durable V9 self-healing ledger.
 *
 * The recorder never selects a repair action, changes health state, grants resource authority, or
 * mutates quarantine state. It only mirrors identities and outcomes that the self-healing subsystem
 * has already persisted. Trace persistence failures therefore remain diagnostic-only.
 */
class SelfHealingDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun record(
        plan: RecoveryPlan,
        snapshot: SelfHealingIncidentSnapshot,
    ): DecisionTraceRecordResult = safeRecord {
        require(plan.nodeId == snapshot.nodeId)
        require(plan.selfHealingFingerprint() == snapshot.planFingerprint)

        val incident = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "self-healing-incident",
            sourceId = snapshot.incidentId.value,
            sourceRevision = 1L,
            recordedAt = snapshot.lastRecordedAt,
        )
        val planNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "self-healing-plan",
            sourceId = snapshot.planFingerprint,
            sourceRevision = 1L,
            displayLabel = plan.source.take(160),
            recordedAt = snapshot.lastRecordedAt,
        )
        val state = DecisionTraceNode.create(
            type = when (snapshot.state) {
                SelfHealingIncidentState.OPEN,
                SelfHealingIncidentState.ACTION_IN_FLIGHT -> DecisionTraceNodeType.OBSERVED_FACT
                SelfHealingIncidentState.BLOCKED -> DecisionTraceNodeType.REJECTION
                SelfHealingIncidentState.RECOVERED,
                SelfHealingIncidentState.EXHAUSTED,
                SelfHealingIncidentState.QUARANTINED -> DecisionTraceNodeType.RECOVERY_OUTCOME
            },
            sourceType = "self-healing-incident-state",
            sourceId = snapshot.incidentId.value,
            sourceRevision = snapshot.ledgerRevision,
            reasonCodes = buildList {
                add("STATE_${snapshot.state.name}")
                add("ATTEMPTED_${snapshot.attemptedActionIds.size}")
                if (snapshot.lastEvidenceSummary != null) add("VERIFICATION_EVIDENCE_PRESENT")
            },
            displayLabel = snapshot.lastDetail?.take(160),
            recordedAt = snapshot.lastRecordedAt,
        )
        val candidates = plan.actions.mapIndexed { index, action ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
                sourceType = "self-healing-recovery-action",
                sourceId = "${snapshot.incidentId.value}:$index:${action.id}",
                sourceRevision = 1L,
                displayLabel = action.id.take(160),
                recordedAt = snapshot.lastRecordedAt,
            )
        }
        val evidence = snapshot.lastEvidenceSummary?.let { summary ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "self-healing-verification-evidence",
                sourceId = "${snapshot.incidentId.value}:${snapshot.ledgerRevision}",
                sourceRevision = snapshot.ledgerRevision,
                reasonCodes = listOf("VERIFICATION_EVIDENCE"),
                displayLabel = summary.take(160),
                recordedAt = snapshot.lastRecordedAt,
            )
        }
        val resourceBlock = snapshot.lastDetail
            ?.takeIf {
                it.startsWith("self-healing-world-budget") ||
                    it == "self-healing-v16-budget-exhausted"
            }
            ?.let { detail ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                    sourceType = "self-healing-resource-block",
                    sourceId = "${snapshot.incidentId.value}:${snapshot.ledgerRevision}",
                    sourceRevision = snapshot.ledgerRevision,
                    reasonCodes = listOf("SELF_HEALING_RESOURCE_BLOCK"),
                    displayLabel = detail.take(160),
                    recordedAt = snapshot.lastRecordedAt,
                )
            }

        val attempted = snapshot.attemptedActionIds.toSet()
        val latestAttempted = snapshot.attemptedActionIds.lastOrNull()
        val nodes = buildList {
            add(incident)
            add(planNode)
            add(state)
            addAll(candidates)
            evidence?.let(::add)
            resourceBlock?.let(::add)
        }
        val links = buildList {
            add(DecisionTraceLink(planNode.id, incident.id, DecisionTraceLinkType.DERIVED_FROM))
            add(DecisionTraceLink(state.id, planNode.id, DecisionTraceLinkType.DERIVED_FROM))
            candidates.forEachIndexed { index, candidate ->
                add(DecisionTraceLink(candidate.id, planNode.id, DecisionTraceLinkType.ALTERNATIVE_TO))
                val actionId = plan.actions[index].id
                if (actionId in attempted) {
                    add(DecisionTraceLink(candidate.id, state.id, DecisionTraceLinkType.SELECTED_BY))
                }
                if (snapshot.state == SelfHealingIncidentState.RECOVERED && actionId == latestAttempted) {
                    add(DecisionTraceLink(candidate.id, state.id, DecisionTraceLinkType.RECOVERED_BY))
                }
            }
            evidence?.let { add(DecisionTraceLink(it.id, state.id, DecisionTraceLinkType.SUPPORTS)) }
            resourceBlock?.let { add(DecisionTraceLink(it.id, state.id, DecisionTraceLinkType.CONSTRAINS)) }
        }

        ledger.append(
            id = DecisionTraceId.create("self-healing-incident", snapshot.incidentId.value),
            nodes = nodes,
            links = links,
        )
    }

    private suspend fun safeRecord(
        operation: suspend () -> DecisionTrace,
    ): DecisionTraceRecordResult = try {
        DecisionTraceRecordResult.Recorded(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DecisionTraceRecordResult.Unavailable(
            stage = "self-healing",
            reason = error.message ?: error::class.simpleName.orEmpty().ifBlank { "trace-unavailable" },
        )
    }
}
